package org.hibernate.benchmark.startup;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures the full SessionFactory creation time including metadata building,
 * type resolution, entity persister initialization, function registry population,
 * and cache setup.
 *
 * Each fork is a fresh JVM with no warmup, measuring true cold-start behavior.
 *
 * Run with:
 *   java -jar basic/target/libs/hibernate-orm-benchmark-basic-1.0-SNAPSHOT-jmh.jar
 *        SessionFactoryStartupBenchmark -f 5 -prof gc
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 5)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
public class SessionFactoryStartupBenchmark {

	@Benchmark
	public void createSessionFactory_h2(Blackhole bh) {
		Configuration cfg = new Configuration()
				.setProperty( "hibernate.dialect", "org.hibernate.dialect.H2Dialect" )
				.setProperty( "hibernate.connection.driver_class", "org.h2.Driver" )
				.setProperty( "hibernate.connection.url", "jdbc:h2:mem:bench_h2;DB_CLOSE_DELAY=-1" )
				.setProperty( "hibernate.connection.username", "sa" )
				.setProperty( "hibernate.hbm2ddl.auto", "create-drop" )
				.setProperty( "hibernate.show_sql", "false" )
				.addAnnotatedClass( Author.class )
				.addAnnotatedClass( Book.class );

		try (SessionFactory sf = cfg.buildSessionFactory()) {
			bh.consume( sf );
		}
	}

	@Benchmark
	public void createSessionFactory_postgresql(Blackhole bh) {
		// Use PostgreSQL dialect with H2 driver — measures PostgreSQL function
		// registry initialization cost without needing an actual PostgreSQL DB.
		// Schema creation is skipped since H2 can't execute PostgreSQL DDL.
		Configuration cfg = new Configuration()
				.setProperty( "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect" )
				.setProperty( "hibernate.connection.driver_class", "org.h2.Driver" )
				.setProperty( "hibernate.connection.url", "jdbc:h2:mem:bench_pg;DB_CLOSE_DELAY=-1" )
				.setProperty( "hibernate.connection.username", "sa" )
				.setProperty( "hibernate.hbm2ddl.auto", "none" )
				.setProperty( "hibernate.show_sql", "false" )
				.addAnnotatedClass( Author.class )
				.addAnnotatedClass( Book.class );

		try (SessionFactory sf = cfg.buildSessionFactory()) {
			bh.consume( sf );
		}
	}

	@Benchmark
	public void createSessionFactory_h2_offline(Blackhole bh) {
		Configuration cfg = new Configuration()
				.setProperty( "hibernate.dialect", "org.hibernate.dialect.H2Dialect" )
				.setProperty( "hibernate.connection.driver_class", "org.h2.Driver" )
				.setProperty( "hibernate.connection.url", "jdbc:h2:mem:bench_h2_offline;DB_CLOSE_DELAY=-1" )
				.setProperty( "hibernate.connection.username", "sa" )
				.setProperty( "hibernate.boot.allow_jdbc_metadata_access", "false" )
				.setProperty( "hibernate.hbm2ddl.auto", "none" )
				.setProperty( "hibernate.show_sql", "false" )
				.addAnnotatedClass( Author.class )
				.addAnnotatedClass( Book.class );

		try (SessionFactory sf = cfg.buildSessionFactory()) {
			bh.consume( sf );
		}
	}

	@Benchmark
	public void createSessionFactory_postgresql_offline(Blackhole bh) {
		Configuration cfg = new Configuration()
				.setProperty( "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect" )
				.setProperty( "hibernate.connection.driver_class", "org.h2.Driver" )
				.setProperty( "hibernate.connection.url", "jdbc:h2:mem:bench_pg_offline;DB_CLOSE_DELAY=-1" )
				.setProperty( "hibernate.connection.username", "sa" )
				.setProperty( "hibernate.boot.allow_jdbc_metadata_access", "false" )
				.setProperty( "hibernate.hbm2ddl.auto", "none" )
				.setProperty( "hibernate.show_sql", "false" )
				.addAnnotatedClass( Author.class )
				.addAnnotatedClass( Book.class );

		try (SessionFactory sf = cfg.buildSessionFactory()) {
			bh.consume( sf );
		}
	}

	@Entity(name = "Author")
	@Table(name = "author")
	public static class Author {
		@Id
		private Long id;
		private String name;
		private String email;

		@OneToMany(mappedBy = "author")
		private List<Book> books;

		public Author() {}
	}

	@Entity(name = "Book")
	@Table(name = "book")
	public static class Book {
		@Id
		private Long id;
		private String title;
		private int year;
		private double price;

		@ManyToOne
		private Author author;

		public Book() {}
	}
}
