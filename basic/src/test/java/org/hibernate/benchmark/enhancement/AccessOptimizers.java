package org.hibernate.benchmark.enhancement;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.NaturalId;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.bytecode.enhance.spi.EnhancementContext;
import org.hibernate.bytecode.enhance.spi.Enhancer;
import org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.bytecode.spi.ProxyFactoryFactory;
import org.hibernate.bytecode.spi.ReflectionOptimizer;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.property.access.spi.PropertyAccess;

import org.hibernate.testing.orm.domain.gambit.EntityOfBasics;
import org.hibernate.testing.orm.domain.gambit.EntityWithLazyOneToOne;
import org.hibernate.testing.orm.domain.gambit.MutableValue;
import org.hibernate.testing.orm.domain.gambit.SimpleEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.metamodel.EntityType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import static org.hibernate.internal.util.NullnessUtil.castNonNull;

/**
 * @author Marco Belladelli
 */
@State(Scope.Thread)
public class AccessOptimizers {
	public enum Access {
		STANDARD, OPTIMIZED
	}

	public enum Morphism {
		ONE, TWO, THREE, FOUR;

		int types() {
			return switch ( this ) {
				case ONE -> 1;
				case TWO -> 2;
				case THREE -> 3;
				case FOUR -> 4;
			};
		}
	}

	@Param
	private Access access;

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
		switch ( access ) {
			case OPTIMIZED -> sessionFactory = getSessionFactory( new TestBytecodeProvider(), types, access );
			case STANDARD -> sessionFactory = getSessionFactory( new ProxyOnlyBytecodeProvider(), types, access );
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
			// this is ensuring that every each queryX method is called enough to be fully compiled
			for ( int i = 0; i < 10_000; i++ ) {
				switch ( types ) {
					case 4:
						query( EntityOfBasics.class, session, bh );
					case 3:
						query( AnotherEntity.class, session, bh );
					case 2:
						query( EntityWithLazyOneToOne.class, session, bh );
					case 1:
						query( SimpleEntity.class, session, bh );
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

	private static void populateData(SessionFactory sf, int count, int types) {
		if ( types < 1 || types > 4 ) {
			throw new IllegalArgumentException( "Invalid types" );
		}
		final Session session = sf.openSession();
		session.getTransaction().begin();
		for ( int i = 1; i <= count; i++ ) {
			final SimpleEntity simpleEntity = randomSimpleEntity( i );
			switch ( types ) {
				case 4:
					session.persist( randomEntityOfBasics( i ) );
				case 3:
					session.persist( randomAnotherEntity( i ) );
				case 2:
					session.persist( randomEntityWithToOne( i, simpleEntity ) );
				case 1:
					session.persist( simpleEntity );
			}
		}
		session.getTransaction().commit();
		session.close();
	}

	private static SimpleEntity randomSimpleEntity(int count) {
		final long timestamp = System.currentTimeMillis() + count * 3_600_000L;
		return new SimpleEntity(
				count,
				new Date( timestamp ),
				Instant.ofEpochMilli( timestamp ),
				count,
				timestamp,
				"simple_entity_" + count
		);
	}

	private static AnotherEntity randomAnotherEntity(int count) {
		final long timestamp = System.currentTimeMillis() + count * 3_600_000L;
		final AnotherEntity entity = new AnotherEntity();
		entity.setId( count );
		entity.setSomeDate( new Date( timestamp ) );
		entity.setSomeInstant( Instant.ofEpochMilli( timestamp ) );
		entity.setSomeInteger( count );
		entity.setSomeLong( timestamp );
		entity.setSomeString( "another_entity_" + count );
		entity.setField0( "field0_" + count );
		entity.setField1( "field1_" + count );
		entity.setField2( "field2_" + count );
		entity.setField3( "field3_" + count );
		entity.setField5( "field4_" + count );
		entity.setField4( "field5_" + count );
		entity.setField6( "field6_" + count );
		entity.setField7( "field7_" + count );
		entity.setField8( "field8_" + count );
		entity.setField9( "field9_" + count );
		return entity;
	}

	@Entity(name = "AnotherEntity")
	static class AnotherEntity {
		private Integer id;
		private Date someDate;
		private Instant someInstant;
		private Integer someInteger;
		private Long someLong;
		private String someString;
		private String field1;
		private String field2;
		private String field3;
		private String field4;
		private String field5;
		private String field6;
		private String field7;
		private String field8;
		private String field9;
		private String field0;

		@Id
		public Integer getId() {
			return id;
		}

		public void setId(Integer id) {
			this.id = id;
		}

		public String getSomeString() {
			return someString;
		}

		public void setSomeString(String someString) {
			this.someString = someString;
		}

		@NaturalId
		public Integer getSomeInteger() {
			return someInteger;
		}

		public void setSomeInteger(Integer someInteger) {
			this.someInteger = someInteger;
		}

		public Long getSomeLong() {
			return someLong;
		}

		public void setSomeLong(Long someLong) {
			this.someLong = someLong;
		}

		@Temporal(TemporalType.TIMESTAMP)
		public Date getSomeDate() {
			return someDate;
		}

		public void setSomeDate(Date someDate) {
			this.someDate = someDate;
		}

		public Instant getSomeInstant() {
			return someInstant;
		}

		public void setSomeInstant(Instant someInstant) {
			this.someInstant = someInstant;
		}

		public String getField1() {
			return field1;
		}

		public void setField1(String field1) {
			this.field1 = field1;
		}

		public String getField2() {
			return field2;
		}

		public void setField2(String field2) {
			this.field2 = field2;
		}

		public String getField3() {
			return field3;
		}

		public void setField3(String field3) {
			this.field3 = field3;
		}

		public String getField4() {
			return field4;
		}

		public void setField4(String field4) {
			this.field4 = field4;
		}

		public String getField5() {
			return field5;
		}

		public void setField5(String field5) {
			this.field5 = field5;
		}

		public String getField6() {
			return field6;
		}

		public void setField6(String field6) {
			this.field6 = field6;
		}

		public String getField7() {
			return field7;
		}

		public void setField7(String field7) {
			this.field7 = field7;
		}

		public String getField8() {
			return field8;
		}

		public void setField8(String field8) {
			this.field8 = field8;
		}

		public String getField9() {
			return field9;
		}

		public void setField9(String field9) {
			this.field9 = field9;
		}

		public String getField0() {
			return field0;
		}

		public void setField0(String field0) {
			this.field0 = field0;
		}
	}

	private static EntityWithLazyOneToOne randomEntityWithToOne(int count, SimpleEntity simpleEntity) {
		final EntityWithLazyOneToOne entity = new EntityWithLazyOneToOne(
				count,
				"entity_with_toone_" + count,
				count
		);
		entity.setOther( simpleEntity );
		return entity;
	}

	private static EntityOfBasics randomEntityOfBasics(int count) {
		final long timestamp = System.currentTimeMillis() + count * 3_600_000L;
		final EntityOfBasics entity = new EntityOfBasics( count );
		entity.setTheBoolean( count % 2 == 0 );
		entity.setTheNumericBoolean( count % 2 == 1 );
		entity.setTheStringBoolean( count % 2 == 0 );
		entity.setTheString( "entity_of_basics_" + count );
		entity.setTheInteger( count );
		entity.setTheInt( count );
		entity.setTheShort( (short) count );
		entity.setTheDouble( count );
		try {
			entity.setTheUrl( new URL( "http://example.com/" + count ) );
		}
		catch (MalformedURLException ignored) {
		}
		entity.setTheDate( new Date( timestamp ) );
		entity.setTheTime( new Date( timestamp ) );
		entity.setTheTimestamp( new Date( timestamp ) );
		entity.setTheInstant( Instant.ofEpochMilli( timestamp ) );
		entity.setGender( entity.isTheBoolean() ? EntityOfBasics.Gender.FEMALE : EntityOfBasics.Gender.MALE );
		entity.setSingleCharGender( entity.isTheBoolean() ? EntityOfBasics.Gender.FEMALE : EntityOfBasics.Gender.MALE );
		entity.setConvertedGender( entity.isTheBoolean() ? EntityOfBasics.Gender.FEMALE : EntityOfBasics.Gender.MALE );
		entity.setOrdinalGender( entity.isTheBoolean() ? EntityOfBasics.Gender.FEMALE : EntityOfBasics.Gender.MALE );
		entity.setTheDuration( null ); // DurationJavaType wraps BigDecimal using costly division
		entity.setTheUuid( UUID.randomUUID() );
		entity.setTheLocalDateTime( LocalDateTime.ofInstant( entity.getTheInstant(), ZoneId.systemDefault() ) );
		entity.setTheLocalDate( LocalDate.ofInstant( entity.getTheInstant(), ZoneId.systemDefault() ) );
		entity.setTheLocalTime( LocalTime.ofInstant( entity.getTheInstant(), ZoneId.systemDefault() ) );
		entity.setTheZonedDateTime( ZonedDateTime.ofInstant( entity.getTheInstant(), ZoneId.systemDefault() ) );
		entity.setTheOffsetDateTime( OffsetDateTime.ofInstant( entity.getTheInstant(), ZoneId.systemDefault() ) );
		entity.setMutableValue( new MutableValue( "mutable_value_" + count ) );
		entity.setTheField( "field_" + count );
		return entity;
	}

	private static SessionFactory getSessionFactory(BytecodeProvider bytecodeProvider, int types, Access access) {
		final Configuration config = new Configuration();
		switch ( types ) {
			case 4:
				config.addAnnotatedClass( EntityOfBasics.class );
			case 3:
				config.addAnnotatedClass( AnotherEntity.class );
			case 2:
				config.addAnnotatedClass( EntityWithLazyOneToOne.class );
			case 1:
				config.addAnnotatedClass( SimpleEntity.class );
		}
		final StandardServiceRegistryBuilder srb = config.getStandardServiceRegistryBuilder();
		srb.applySetting( AvailableSettings.SHOW_SQL, false )
				.applySetting( AvailableSettings.LOG_SESSION_METRICS, false )
				.applySetting(
						AvailableSettings.DIALECT,
						"org.hibernate.dialect.H2Dialect"
				)
				.applySetting( AvailableSettings.JAKARTA_JDBC_DRIVER, "org.h2.Driver" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_USER, "sa" )
				.applySetting( AvailableSettings.HBM2DDL_AUTO, "create-drop" );
		// force no runtime bytecode-enhancement
		srb.addService( BytecodeProvider.class, bytecodeProvider );
		final SessionFactoryImplementor sf = (SessionFactoryImplementor) config.buildSessionFactory( srb.build() );

		for ( EntityType<?> entityType : sf.getJpaMetamodel().getEntities() ) {
			final ReflectionOptimizer optimizer = sf.getMappingMetamodel()
					.getEntityDescriptor( entityType.getJavaType() )
					.getRepresentationStrategy()
					.getReflectionOptimizer();
			if ( access == Access.STANDARD ) {
				assert optimizer == null;
			}
			else {
				//noinspection ConstantValue
				assert optimizer != null && optimizer.getAccessOptimizer() != null;
			}
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
		final int count = switch ( nextEntityTypeId ) {
			case 0 -> query( SimpleEntity.class, session, bh );
			case 1 -> query( EntityWithLazyOneToOne.class, session, bh );
			case 2 -> query( AnotherEntity.class, session, bh );
			case 3 -> query( EntityOfBasics.class, session, bh );
			default -> throw new AssertionError( "it shouldn't happen, entity type id: " + nextEntityTypeId );
		};
		assert count == this.count;
		return count;
	}

	private static int query(Class<?> entityClass, Session session, Blackhole bh) {
		final List<?> resultList = session.createQuery(
				"from " + entityClass.getSimpleName(),
				entityClass
		).getResultList();
		int count = 0;
		for ( Object result : resultList ) {
			if ( bh != null ) {
				bh.consume( result );
			}
			count++;
		}
		session.clear();
		return count;
	}

	/**
	 * Test {@link BytecodeProvider} which provides the different access optimizers
	 */
	static class TestBytecodeProvider implements BytecodeProvider {
		private final BytecodeProviderImpl delegate = new BytecodeProviderImpl();

		@Override
		public ProxyFactoryFactory getProxyFactoryFactory() {
			return delegate.getProxyFactoryFactory();
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
			final ReflectionOptimizer optimizer = delegate.getReflectionOptimizer( clazz, propertyAccessMap );
			return new ReflectionOptimizer() {
				@Override
				public InstantiationOptimizer getInstantiationOptimizer() {
					return null;
				}

				@Override
				public AccessOptimizer getAccessOptimizer() {
					return castNonNull( optimizer ).getAccessOptimizer();
				}
			};

		}

		@Override
		public Enhancer getEnhancer(EnhancementContext enhancementContext) {
			return null;
		}
	}

	/**
	 * Empty {@link BytecodeProvider} which only provides proxy functionality
	 */
	static class ProxyOnlyBytecodeProvider implements BytecodeProvider {
		private final BytecodeProviderImpl delegate = new BytecodeProviderImpl();

		@Override
		public ProxyFactoryFactory getProxyFactoryFactory() {
			return delegate.getProxyFactoryFactory();
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
			return null;
		}

		@Override
		public Enhancer getEnhancer(EnhancementContext enhancementContext) {
			return null;
		}
	}

	public static void main(String[] args) throws Exception {
		final AccessOptimizers benchmark = new AccessOptimizers();
		benchmark.morphism = Morphism.FOUR;
		benchmark.count = 100;
		benchmark.access = Access.OPTIMIZED;

		benchmark.setup( null );
		query( SimpleEntity.class, benchmark.session, null );
	}
}
