package org.hibernate.benchmark.enhancement;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.AssertionFailure;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
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

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

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

	// WHen true, we make JIT compile all Morphism versions of the method but then run only 1 type
	@Param({ "false", "true" })
	private boolean polluteAtWarmup;

	@Param({ "100" })
	private int count;

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
		switch ( instantiation ) {
			case OPTIMIZED -> sessionFactory = getSessionFactory(
					new FortuneBytecodeProvider(),
					EntityInstantiatorPojoOptimized.class,
					types
			);
			case STANDARD -> sessionFactory = getSessionFactory(
					new BytecodeProviderImpl(),
					EntityInstantiatorPojoStandard.class,
					types
			);
		}
		populateData( sessionFactory, count, types );
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
						queryFortune3( session, bh );
					case 3:
						queryFortune2( session, bh );
					case 2:
						queryFortune1( session, bh );
					case 1:
						queryFortune0( session, bh );
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
		final Session session = sf.openSession();
		session.getTransaction().begin();
		switch ( types ) {
			case 4:
				for ( int i = 0; i < count; i++ ) {
					session.persist( new Fortune3( i ) );
				}
			case 3:
				for ( int i = 0; i < count; i++ ) {
					session.persist( new Fortune2( i ) );
				}
			case 2:
				for ( int i = 0; i < count; i++ ) {
					session.persist( new Fortune1( i ) );
				}
			case 1:
				for ( int i = 0; i < count; i++ ) {
					session.persist( new Fortune0( i ) );
				}
		}
		session.getTransaction().commit();
		session.close();
	}

	protected SessionFactory getSessionFactory(
			BytecodeProvider bytecodeProvider,
			Class<?> expectedInstantiatorClass,
			int types) {
		final Configuration config = new Configuration();
		switch ( types ) {
			case 4:
				config.addAnnotatedClass( Fortune3.class );
			case 3:
				config.addAnnotatedClass( Fortune2.class );
			case 2:
				config.addAnnotatedClass( Fortune1.class );
			case 1:
				config.addAnnotatedClass( Fortune0.class );
		}
		final StandardServiceRegistryBuilder srb = config.getStandardServiceRegistryBuilder();
		srb.applySetting( AvailableSettings.SHOW_SQL, false )
				.applySetting( AvailableSettings.LOG_SESSION_METRICS, false )
				.applySetting( AvailableSettings.DIALECT, "org.hibernate.dialect.H2Dialect" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_DRIVER, "org.h2.Driver" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_USER, "sa" )
				.applySetting( AvailableSettings.HBM2DDL_AUTO, "create-drop" );
		// force no runtime bytecode-enhancement
		srb.addService( BytecodeProvider.class, bytecodeProvider );
		final SessionFactoryImplementor sf = (SessionFactoryImplementor) config.buildSessionFactory( srb.build() );
		final EntityInstantiator[] instantiators = new EntityInstantiator[types];
		switch ( types ) {
			case 4:
				instantiators[3] = sf.getMappingMetamodel()
						.getEntityDescriptor( Fortune3.class )
						.getRepresentationStrategy()
						.getInstantiator();
			case 3:
				instantiators[2] = sf.getMappingMetamodel()
						.getEntityDescriptor( Fortune2.class )
						.getRepresentationStrategy()
						.getInstantiator();
			case 2:
				instantiators[1] = sf.getMappingMetamodel()
						.getEntityDescriptor( Fortune1.class )
						.getRepresentationStrategy()
						.getInstantiator();
			case 1:
				instantiators[0] = sf.getMappingMetamodel()
						.getEntityDescriptor( Fortune0.class )
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
		return sf;
	}

	@TearDown
	public void destroy() {
		session.getTransaction().commit();
		session.close();
	}

	@Benchmark
	public int query(Blackhole bh) {
		int nextEntityTypeId = nextEntityTypeId();
		final int count;
		switch ( nextEntityTypeId ) {
			case 0:
				count = queryFortune0( session, bh );
				break;
			case 1:
				count = queryFortune1( session, bh );
				break;
			case 2:
				count = queryFortune2( session, bh );
				break;
			case 3:
				count = queryFortune3( session, bh );
				break;
			default:
				throw new AssertionError( "it shouldn't happen!" );
		}
		return count;
	}

	@CompilerControl(CompilerControl.Mode.DONT_INLINE)
	private static int queryFortune2(Session session, Blackhole bh) {
		final List<Fortune2> results = session.createQuery( "from Fortune2", Fortune2.class ).getResultList();
		int count = 0;
		for ( Fortune2 fortune : results ) {
			if ( bh != null ) {
				bh.consume( fortune );
			}
			count++;
		}
		session.clear();
		return count;
	}

	@CompilerControl(CompilerControl.Mode.DONT_INLINE)
	private static int queryFortune3(Session session, Blackhole bh) {
		final List<Fortune3> results = session.createQuery( "from Fortune3", Fortune3.class ).getResultList();
		int count = 0;
		for ( Fortune3 fortune : results ) {
			if ( bh != null ) {
				bh.consume( fortune );
			}
			count++;
		}
		session.clear();
		return count;
	}

	@CompilerControl(CompilerControl.Mode.DONT_INLINE)
	private static int queryFortune1(Session session, Blackhole bh) {
		final List<Fortune1> results = session.createQuery( "from Fortune1", Fortune1.class ).getResultList();
		int count = 0;
		for ( Fortune1 fortune : results ) {
			if ( bh != null ) {
				bh.consume( fortune );
			}
			count++;
		}
		session.clear();
		return count;
	}

	@CompilerControl(CompilerControl.Mode.DONT_INLINE)
	private static int queryFortune0(Session session, Blackhole bh) {
		final List<Fortune0> results = session.createQuery( "from Fortune0", Fortune0.class ).getResultList();
		int count = 0;
		for ( Fortune0 fortune0 : results ) {
			if ( bh != null ) {
				bh.consume( fortune0 );
			}
			count++;
		}
		session.clear();
		return count;
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
