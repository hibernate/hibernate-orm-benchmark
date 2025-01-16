package org.hibernate.benchmark.collections;

import java.util.List;
import java.util.Set;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;

import org.hibernate.testing.orm.domain.gambit.EntityOfLists;
import org.hibernate.testing.orm.domain.gambit.EntityOfMaps;
import org.hibernate.testing.orm.domain.gambit.SimpleEntity;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Tests the performance of handling persistent collections in the persistence context.
 */
@SuppressWarnings("unused")
@State(Scope.Thread)
public class PersistentCollections {
	public enum CollectionType {
		SET, // 2 collections
		LIST, // 7 collections
		MAP; // 16 collections

		int id() {
			return switch ( this ) {
				case SET -> 1;
				case LIST -> 2;
				case MAP -> 3;
			};
		}
	}

	@Param
	private CollectionType collections;

	@Param({ "100" })
	private int count;

	@Param({ "false", "true" })
	private boolean init_collections;

	private SessionFactory sessionFactory;
	private Session session;

	@Setup
	public void setup(Blackhole bh) {
		final Class<?> entityClass = entityClass( collections.id() );
		sessionFactory = getSessionFactory( entityClass );
		populateData( sessionFactory, count, entityClass );
		session = sessionFactory.openSession();
		session.getTransaction().begin();
	}

	@TearDown
	public void destroy() {
		session.getTransaction().commit();
		session.close();
		sessionFactory.close();
	}

	private static Class<?> entityClass(int id) {
		return switch ( id ) {
			case 3 -> EntityOfMaps.class;
			case 2 -> EntityOfLists.class;
			case 1 -> EntityOfSets.class;
			default -> throw new IllegalStateException( "Unexpected value: " + id );
		};
	}

	private static SessionFactory getSessionFactory(Class<?> entityClass) {
		final Configuration config = new Configuration();
		// we always need SimpleEntity to be mapped for to-many associations
		config.addAnnotatedClass( SimpleEntity.class );
		config.addAnnotatedClass( entityClass );
		final StandardServiceRegistryBuilder srb = config.getStandardServiceRegistryBuilder();
		srb.applySetting( AvailableSettings.SHOW_SQL, false )
				.applySetting( AvailableSettings.LOG_SESSION_METRICS, false )
				.applySetting( AvailableSettings.DIALECT, "org.hibernate.dialect.H2Dialect" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_DRIVER, "org.h2.Driver" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_URL, "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1" )
				.applySetting( AvailableSettings.JAKARTA_JDBC_USER, "sa" )
				.applySetting( AvailableSettings.HBM2DDL_AUTO, "create-drop" );
		return config.buildSessionFactory( srb.build() );
	}

	private void populateData(SessionFactory sf, int count, Class<?> entityClass) {
		// We don't actually need to populate collections to highlight the performance of the Persistence Context
		final Session session = sf.openSession();
		session.getTransaction().begin();

		switch ( entityClass.getSimpleName() ) {
			case "EntityOfSets":
				for ( int i = 0; i < count; i++ ) {
					session.persist( new EntityOfSets( i, "sets_" + i ) );
				}
				break;
			case "EntityOfLists":
				for ( int i = 0; i < count; i++ ) {
					session.persist( new EntityOfLists( i, "lists_" + i ) );
				}
				break;
			case "EntityOfMaps":
				for ( int i = 0; i < count; i++ ) {
					session.persist( new EntityOfMaps( i, "maps_" + i ) );
				}
				break;
		}

		session.getTransaction().commit();
		session.close();
	}

	@Benchmark
	public int query(Blackhole bh) {
		final Class<?> entityType = entityClass( collections.id() );
		final List<?> results = queryEntity( entityType, session );
		if ( init_collections ) {
			initCollections( results, entityType );
		}
		int resultCount = 0;
		for ( Object result : results ) {
			if ( bh != null ) {
				bh.consume( result );
			}
			resultCount++;
		}
		session.clear();
		return resultCount;
	}

	private static <T> List<T> queryEntity(Class<T> entityClass, Session session) {
		return session.createQuery(
				"from " + entityClass.getSimpleName(),
				entityClass
		).getResultList();
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	private static void initCollections(List<?> results, Class<?> entityType) {
		// force initialization of all associated collections
		switch ( entityType.getSimpleName() ) {
			case "EntityOfSets":
				for ( Object result : results ) {
					final EntityOfSets entityOfSets = (EntityOfSets) result;
					entityOfSets.getSetOfStrings().size();
					entityOfSets.getSetOfEntities().size();
				}
				break;
			case "EntityOfLists":
				for ( Object result : results ) {
					final EntityOfLists entityOfLists = (EntityOfLists) result;
					entityOfLists.getListOfBasics().size();
					entityOfLists.getListOfNumbers().size();
					entityOfLists.getListOfConvertedEnums().size();
					entityOfLists.getListOfEnums().size();
					entityOfLists.getListOfComponents().size();
					entityOfLists.getListOfOneToMany().size();
					entityOfLists.getListOfManyToMany().size();
				}
				break;
			case "EntityOfMaps":
				for ( Object result : results ) {
					final EntityOfMaps entityOfMaps = (EntityOfMaps) result;
					entityOfMaps.getBasicByBasic().size();
					entityOfMaps.getNumberByNumber().size();
					entityOfMaps.getSortedBasicByBasic().size();
					entityOfMaps.getSortedBasicByBasicWithComparator().size();
					entityOfMaps.getSortedBasicByBasicWithSortNaturalByDefault().size();
					entityOfMaps.getBasicByEnum().size();
					entityOfMaps.getBasicByConvertedEnum().size();
					entityOfMaps.getComponentByBasic().size();
					entityOfMaps.getBasicByComponent().size();
					entityOfMaps.getOneToManyByBasic().size();
					entityOfMaps.getBasicByOneToMany().size();
					entityOfMaps.getManyToManyByBasic().size();
					entityOfMaps.getComponentByBasicOrdered().size();
					entityOfMaps.getSortedManyToManyByBasic().size();
					entityOfMaps.getSortedManyToManyByBasicWithComparator().size();
					entityOfMaps.getSortedManyToManyByBasicWithSortNaturalByDefault().size();
				}
				break;
		}
	}

	@Entity(name = "EntityOfSets")
	private static final class EntityOfSets {
		@Id
		private Integer id;

		private String name;

		@ElementCollection
		private Set<String> setOfStrings;

		@OneToMany
		private Set<SimpleEntity> setOfEntities;

		public EntityOfSets() {
		}

		public EntityOfSets(Integer id, String name) {
			this.id = id;
			this.name = name;
		}

		public Set<String> getSetOfStrings() {
			return setOfStrings;
		}

		public Set<SimpleEntity> getSetOfEntities() {
			return setOfEntities;
		}
	}
}
