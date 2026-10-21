package org.hibernate.benchmark.filter;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Persistence;
import jakarta.persistence.Table;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * An HQL list query in a new session for each operation. Each operation uses
 * the next tenant, so consecutive sessions have different filter arguments.
 * {@code withTenantCondition} runs the same SQL as {@code withFilter} without
 * a filter, so the difference between the two is the cost of the filter.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class QueryWithEnabledFilter {
	private static final int TENANTS = 20;
	private static final int ITEMS_PER_TENANT = 10;
	private static final String HQL = "from Item i where i.status = :status order by i.id";
	private static final String HQL_TENANT =
			"from Item i where i.status = :status and i.tenantId = :tenantId order by i.id";

	private SessionFactory sessionFactory;
	private long tenant;

	@Setup
	public void setup() {
		sessionFactory = Persistence.createEntityManagerFactory( "QueryWithEnabledFilter" )
				.unwrap( SessionFactory.class );
		sessionFactory.inTransaction( session -> {
			long id = 0;
			for ( long tenantId = 0; tenantId < TENANTS; tenantId++ ) {
				for ( int i = 0; i < ITEMS_PER_TENANT; i++ ) {
					session.persist( new Item( id++, tenantId, "active", "item " + id ) );
				}
			}
		} );
	}

	@TearDown
	public void destroy() {
		sessionFactory.close();
	}

	@Benchmark
	public void withFilter(Blackhole bh) {
		try ( Session session = sessionFactory.openSession() ) {
			session.enableFilter( "tenant" ).setParameter( "tenantId", nextTenant() );
			consume( bh, query( session ) );
		}
	}

	@Benchmark
	public void withTenantCondition(Blackhole bh) {
		try ( Session session = sessionFactory.openSession() ) {
			consume( bh, session.createSelectionQuery( HQL_TENANT, Item.class )
					.setParameter( "status", "active" )
					.setParameter( "tenantId", nextTenant() )
					.setMaxResults( ITEMS_PER_TENANT )
					.getResultList() );
		}
	}

	@Benchmark
	public void withoutFilter(Blackhole bh) {
		try ( Session session = sessionFactory.openSession() ) {
			consume( bh, query( session ) );
		}
	}

	private long nextTenant() {
		tenant = ( tenant + 1 ) % TENANTS;
		return tenant;
	}

	private static List<Item> query(Session session) {
		return session.createSelectionQuery( HQL, Item.class )
				.setParameter( "status", "active" )
				.setMaxResults( ITEMS_PER_TENANT )
				.getResultList();
	}

	private static void consume(Blackhole bh, List<Item> items) {
		if ( bh != null ) {
			for ( Item item : items ) {
				bh.consume( item );
			}
		}
	}

	@FilterDef(name = "tenant", defaultCondition = "tenant_id = :tenantId",
			parameters = @ParamDef(name = "tenantId", type = Long.class))
	@Filter(name = "tenant")
	@Entity(name = "Item")
	@Table(name = "filter_bench_item", indexes = @Index(columnList = "tenant_id"))
	public static class Item {
		@Id
		public Long id;
		@Column(name = "tenant_id")
		public Long tenantId;
		public String status;
		public String name;

		protected Item() {
		}

		public Item(Long id, Long tenantId, String status, String name) {
			this.id = id;
			this.tenantId = tenantId;
			this.status = status;
			this.name = name;
		}
	}

	public static void main(String[] args) {
		QueryWithEnabledFilter benchmark = new QueryWithEnabledFilter();
		benchmark.setup();
		benchmark.withFilter( null );
		benchmark.destroy();
	}
}
