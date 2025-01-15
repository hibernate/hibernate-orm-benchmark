package org.hibernate.benchmark.enhancement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.hibernate.AssertionFailure;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.Immutable;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.bytecode.enhance.spi.EnhancementContext;
import org.hibernate.bytecode.enhance.spi.Enhancer;
import org.hibernate.bytecode.internal.none.BytecodeProviderImpl;
import org.hibernate.bytecode.spi.BasicProxyFactory;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.bytecode.spi.ProxyFactoryFactory;
import org.hibernate.bytecode.spi.ReflectionOptimizer;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.metamodel.internal.EntityInstantiatorPojoOptimized;
import org.hibernate.metamodel.internal.EntityInstantiatorPojoStandard;
import org.hibernate.metamodel.spi.EntityInstantiator;
import org.hibernate.property.access.spi.PropertyAccess;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.ProxyFactory;
import org.hibernate.type.CompositeType;

import org.hibernate.testing.orm.domain.gambit.EntityOfBasics;
import org.hibernate.testing.orm.domain.gambit.EntityOfMaps;
import org.hibernate.testing.orm.domain.gambit.EntityWithLazyOneToOne;
import org.hibernate.testing.orm.domain.gambit.SimpleEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import static org.hibernate.benchmark.enhancement.EnhancementUtils.buildEnhancerClassLoader;

/**
 * @author Marco Belladelli
 */
@State(Scope.Thread)
public class EntityInstantiators {

	public enum Instantiation {
		STANDARD, OPTIMIZED
	}

	public enum Morphism {
		MONO, BI, TRI, QUAD;

		int types() {
			return switch ( this ) {
				case MONO -> 1;
				case BI -> 2;
				case TRI -> 3;
				case QUAD -> 4;
			};
		}
	}

	@Param
	private Instantiation instantiation;

	@Param
	private Morphism morphism;

	// When true, we make JIT compile all Morphism versions of the method but then run only 1 type
	@Param({ "false", "true" })
	private boolean polluteAtWarmup;

	@Param({ "100" })
	private int count;

	// When true, use immutable entities
	@Param({ "false", "true" })
	private boolean mutable;

	// When true, enhance entity classes at runtime
	@Param({ "false", "true" })
	private boolean enhance;

	private SessionFactory sessionFactory;
	private Session session;

	int nextEntityTypeId;
	int[] entityTypes;

	@Setup
	public void setup(Blackhole bh) {
		int types = morphism.types();
		if ( types == 1 && polluteAtWarmup ) {
			// this is fairly useless
			throw new IllegalStateException( "Cannot pollute with monomorphic types" );
		}
		final List<Class<?>> entityClasses = initEntityClasses( types, mutable );
		populateData( getSessionFactory( new BytecodeProviderImpl(), entityClasses, false ), count, types );
		switch ( instantiation ) {
			case OPTIMIZED:
				sessionFactory = getSessionFactory( new FortuneBytecodeProvider(), entityClasses, enhance );
				assertEntityInstantiators( sessionFactory, EntityInstantiatorPojoOptimized.class, types );
				break;
			case STANDARD:
				sessionFactory = getSessionFactory( new BytecodeProviderImpl(), entityClasses, enhance );
				assertEntityInstantiators( sessionFactory, EntityInstantiatorPojoStandard.class, types );
		}
		session = sessionFactory.openSession();
		session.getTransaction().begin();

		nextEntityTypeId = 0;
		entityTypes = new int[types];
		for ( int i = 0; i < types; i++ ) {
			entityTypes[i] = i;
		}
		if ( polluteAtWarmup ) {
			// this is ensuring that every each queryFortuneX method is called enough to be fully compiled
			for ( int i = 0; i < 10_000; i++ ) {
				switch ( types ) {
					case 4:
						queryFortune( fortune3Class(), session, bh );
					case 3:
						queryFortune( fortune2Class(), session, bh );
					case 2:
						queryFortune( fortune1Class(), session, bh );
					case 1:
						queryFortune( fortune0Class(), session, bh );
				}
			}
			// force to make it monomorphic now
			entityTypes = new int[] { 0 };
			nextEntityTypeId = 0;
		}
	}

	private int nextEntityTypeId() {
		var entityTypes = this.entityTypes;
		if ( nextEntityTypeId >= entityTypes.length ) {
			nextEntityTypeId = 1;
			return entityTypes[0];
		}
		else {
			return entityTypes[nextEntityTypeId++];
		}
	}

	private void populateData(SessionFactory sf, int count, int types) {
		if ( types == 0 || types > 4 ) {
			throw new IllegalArgumentException( "Invalid types" );
		}
		sf.getSchemaManager().exportMappedObjects( false );
		final Session session = sf.openSession();
		session.getTransaction().begin();
		switch ( types ) {
			case 4:
				for ( int i = 0; i < count; i++ ) {
					session.persist( mutable ? new Fortune3( i ) : new Fortune3Immutable( i ) );
				}
			case 3:
				for ( int i = 0; i < count; i++ ) {
					session.persist( mutable ? new Fortune2( i ) : new Fortune2Immutable( i ) );
				}
			case 2:
				for ( int i = 0; i < count; i++ ) {
					session.persist( mutable ? new Fortune1( i ) : new Fortune1Immutable( i ) );
				}
			case 1:
				for ( int i = 0; i < count; i++ ) {
					session.persist( mutable ? new Fortune0( i ) : new Fortune0Immutable( i ) );
				}
		}
		session.getTransaction().commit();
		session.close();
		sf.close();
	}

	private List<Class<?>> initEntityClasses(int types, boolean mutable) {
		final List<Class<?>> classes = new ArrayList<>();
		switch ( types ) {
			case 4:
				classes.add( fortune3Class() );
			case 3:
				classes.add( fortune2Class() );
			case 2:
				classes.add( fortune1Class() );
			case 1:
				classes.add( fortune0Class() );
		}
		return classes;
	}

	private SessionFactory getSessionFactory(
			BytecodeProvider bytecodeProvider,
			List<Class<?>> entityClasses,
			boolean enhance) {
		final BootstrapServiceRegistryBuilder bsrb = new BootstrapServiceRegistryBuilder();
		if ( enhance ) {
			final ClassLoader enhancerClassLoader = buildEnhancerClassLoader(
					entityClasses.stream().map( Class::getName ).collect( Collectors.toSet() )
			);
			bsrb.applyClassLoader( enhancerClassLoader );
		}
		final Configuration config = new Configuration( bsrb.build() );
		entityClasses.forEach( config::addAnnotatedClass );
		final StandardServiceRegistryBuilder srb = config.getStandardServiceRegistryBuilder();
		srb.applySetting( AvailableSettings.SHOW_SQL, false )
				.applySetting( AvailableSettings.LOG_SESSION_METRICS, false )
				.applySetting( AvailableSettings.DIALECT, "org.hibernate.dialect.H2Dialect" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_DRIVER, "org.h2.Driver" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_USER, "sa" );
		// force no runtime bytecode-enhancement
		srb.addService( BytecodeProvider.class, bytecodeProvider );
		return config.buildSessionFactory( srb.build() );
	}

	private void assertEntityInstantiators(
			SessionFactory sessionFactory,
			Class<?> expectedInstantiatorClass,
			int types) {
		final EntityInstantiator[] instantiators = new EntityInstantiator[types];
		final SessionFactoryImplementor sf = (SessionFactoryImplementor) sessionFactory;
		switch ( types ) {
			case 4:
				instantiators[3] = sf.getMappingMetamodel()
						.getEntityDescriptor( fortune3Class() )
						.getRepresentationStrategy()
						.getInstantiator();
			case 3:
				instantiators[2] = sf.getMappingMetamodel()
						.getEntityDescriptor( fortune2Class() )
						.getRepresentationStrategy()
						.getInstantiator();
			case 2:
				instantiators[1] = sf.getMappingMetamodel()
						.getEntityDescriptor( fortune1Class() )
						.getRepresentationStrategy()
						.getInstantiator();
			case 1:
				instantiators[0] = sf.getMappingMetamodel()
						.getEntityDescriptor( fortune0Class() )
						.getRepresentationStrategy()
						.getInstantiator();
		}
		boolean found = false;
		for ( EntityInstantiator instantiator : instantiators ) {
			found |= expectedInstantiatorClass.isInstance( instantiator );
		}
		if ( !found ) {
			throw new AssertionFailure( "Expected instantiator not found" );
		}
	}

	@TearDown
	public void destroy() {
		session.getTransaction().commit();
		session.close();
		sessionFactory.getSchemaManager().dropMappedObjects( false );
		sessionFactory.close();
	}

	@Benchmark
	public int query(Blackhole bh) {
		int nextEntityTypeId = nextEntityTypeId();
		final int count = switch ( nextEntityTypeId ) {
			case 0 -> queryFortune( fortune0Class(), session, bh );
			case 1 -> queryFortune( fortune1Class(), session, bh );
			case 2 -> queryFortune( fortune2Class(), session, bh );
			case 3 -> queryFortune( fortune3Class(), session, bh );
			default -> throw new AssertionError( "it shouldn't happen!" );
		};
		assert count == this.count;
		return count;
	}

	@CompilerControl(CompilerControl.Mode.DONT_INLINE)
	private static int queryFortune(Class<?> entityClass, Session session, Blackhole bh) {
		final List<Object> results = session.createQuery(
				"from " + entityClass.getSimpleName(),
				Object.class
		).getResultList();
		int count = 0;
		for ( Object result : results ) {
			if ( bh != null ) {
				bh.consume( result );
			}
			count++;
		}
		session.clear();
		return count;
	}

	private Class<?> fortune0Class() {
		return mutable ? Fortune0.class : Fortune0Immutable.class;
	}

	private Class<?> fortune1Class() {
		return mutable ? Fortune1.class : Fortune1Immutable.class;
	}

	private Class<?> fortune2Class() {
		return mutable ? Fortune2.class : Fortune2Immutable.class;
	}

	private Class<?> fortune3Class() {
		return mutable ? Fortune3.class : Fortune3Immutable.class;
	}

	@Entity(name = "Fortune0")
	static class Fortune0 {
		@Id
		public Integer id;

		public Fortune0() {
		}

		public Fortune0(Integer id) {
			this.id = id;
		}
	}

	@Immutable
	@Entity(name = "Fortune0Immutable")
	static class Fortune0Immutable {
		@Id
		public Integer id;

		public Fortune0Immutable() {
		}

		public Fortune0Immutable(Integer id) {
			this.id = id;
		}
	}

	@Entity(name = "Fortune1")
	static class Fortune1 {
		@Id
		public Integer id;

		public Fortune1() {
		}

		public Fortune1(Integer id) {
			this.id = id;
		}
	}

	@Immutable
	@Entity(name = "Fortune1Immutable")
	static class Fortune1Immutable {
		@Id
		public Integer id;

		public Fortune1Immutable() {
		}

		public Fortune1Immutable(Integer id) {
			this.id = id;
		}
	}

	@Entity(name = "Fortune2")
	static class Fortune2 {
		@Id
		public Integer id;

		public Fortune2() {
		}

		public Fortune2(Integer id) {
			this.id = id;
		}
	}

	@Immutable
	@Entity(name = "Fortune2Immutable")
	static class Fortune2Immutable {
		@Id
		public Integer id;

		public Fortune2Immutable() {
		}

		public Fortune2Immutable(Integer id) {
			this.id = id;
		}
	}

	@Entity(name = "Fortune3")
	static class Fortune3 {
		@Id
		public Integer id;

		public Fortune3() {
		}

		public Fortune3(Integer id) {
			this.id = id;
		}
	}

	@Immutable
	@Entity(name = "Fortune3Immutable")
	static class Fortune3Immutable {
		@Id
		public Integer id;

		public Fortune3Immutable() {
		}

		public Fortune3Immutable(Integer id) {
			this.id = id;
		}
	}

	/**
	 * Empty {@link BytecodeProvider} which only provides the different Fortune static optimizers
	 */
	static class FortuneBytecodeProvider implements BytecodeProvider {
		@Override
		public ProxyFactoryFactory getProxyFactoryFactory() {
			return new NoProxyFactoryFactory();
		}

		@SuppressWarnings("removal")
		@Override
		public ReflectionOptimizer getReflectionOptimizer(
				Class clazz,
				String[] getterNames,
				String[] setterNames,
				Class[] types) {
			return null;
		}

		@Override
		public ReflectionOptimizer getReflectionOptimizer(
				Class<?> clazz,
				Map<String, PropertyAccess> propertyAccessMap) {
			if ( clazz == Fortune0.class ) {
				return new ReflectionOptimizer() {
					@Override
					public InstantiationOptimizer getInstantiationOptimizer() {
						return new Fortune0Instantiator();
					}

					@Override
					public AccessOptimizer getAccessOptimizer() {
						return null;
					}
				};
			}
			else if ( clazz == Fortune1.class ) {
				return new ReflectionOptimizer() {
					@Override
					public InstantiationOptimizer getInstantiationOptimizer() {
						return new Fortune1Instantiator();
					}

					@Override
					public AccessOptimizer getAccessOptimizer() {
						return null;
					}
				};
			}
			else if ( clazz == Fortune2.class ) {
				return new ReflectionOptimizer() {
					@Override
					public InstantiationOptimizer getInstantiationOptimizer() {
						return new Fortune2Instantiator();
					}

					@Override
					public AccessOptimizer getAccessOptimizer() {
						return null;
					}
				};
			}
			else {
				return new ReflectionOptimizer() {
					@Override
					public InstantiationOptimizer getInstantiationOptimizer() {
						return new Fortune3Instantiator();
					}

					@Override
					public AccessOptimizer getAccessOptimizer() {
						return null;
					}
				};
			}
		}

		@Override
		public Enhancer getEnhancer(EnhancementContext enhancementContext) {
			return null;
		}
	}

	/**
	 * Implementation of {@link ReflectionOptimizer.InstantiationOptimizer}
	 * which reflects the generated bytecode in both Hibernate and Quarkus
	 */
	static class Fortune0Instantiator implements ReflectionOptimizer.InstantiationOptimizer {
		@Override
		public Object newInstance() {
			return new Fortune0();
		}
	}

	static class Fortune1Instantiator implements ReflectionOptimizer.InstantiationOptimizer {
		@Override
		public Object newInstance() {
			return new Fortune1();
		}
	}

	static class Fortune2Instantiator implements ReflectionOptimizer.InstantiationOptimizer {
		@Override
		public Object newInstance() {
			return new Fortune2();
		}
	}

	static class Fortune3Instantiator implements ReflectionOptimizer.InstantiationOptimizer {
		@Override
		public Object newInstance() {
			return new Fortune3();
		}
	}

	static class NoProxyFactoryFactory implements ProxyFactoryFactory {
		@Override
		public ProxyFactory buildProxyFactory(SessionFactoryImplementor sessionFactory) {
			return new ProxyFactory() {
				@Override
				public void postInstantiate(
						String entityName,
						Class<?> persistentClass,
						Set<Class<?>> interfaces,
						Method getIdentifierMethod,
						Method setIdentifierMethod,
						CompositeType componentIdType) throws HibernateException {
				}

				@Override
				public HibernateProxy getProxy(Object id, SharedSessionContractImplementor session)
						throws HibernateException {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Override
		public BasicProxyFactory buildBasicProxyFactory(Class superClassOrInterface) {
			return new BasicProxyFactory() {
				@Override
				public Object getProxy() {
					throw new UnsupportedOperationException();
				}
			};
		}
	}
}
