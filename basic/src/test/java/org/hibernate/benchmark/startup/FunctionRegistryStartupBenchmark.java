package org.hibernate.benchmark.startup;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.query.sqm.function.SqmFunctionDescriptor;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.testing.orm.junit.DialectFeatureChecks;
import org.hibernate.type.spi.TypeConfiguration;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Measures the cost of SQL function registry initialization during
 * Hibernate SessionFactory startup.
 *
 * Run with:
 *   java -jar basic/target/libs/hibernate-orm-benchmark-basic-1.0-SNAPSHOT-jmh.jar
 *        FunctionRegistryStartupBenchmark -f 3 -prof gc
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
public class FunctionRegistryStartupBenchmark {

	@Benchmark
	public SqmFunctionRegistry initializeFunctionRegistry_postgresql(Blackhole bh) {
		TypeConfiguration typeConfiguration = new TypeConfiguration();
		SqmFunctionRegistry registry = new SqmFunctionRegistry();
		typeConfiguration.scope(
				new DialectFeatureChecks.FakeMetadataBuildingContext( typeConfiguration, registry ) );
		PostgreSQLDialect dialect = new PostgreSQLDialect();
		FunctionContributions contributions =
				new DialectFeatureChecks.FakeFunctionContributions( dialect, typeConfiguration, registry );
		dialect.initializeFunctionRegistry( contributions );
		bh.consume( registry.getValidFunctionKeys().size() );
		return registry;
	}

	@Benchmark
	public SqmFunctionRegistry initializeFunctionRegistry_h2(Blackhole bh) {
		TypeConfiguration typeConfiguration = new TypeConfiguration();
		SqmFunctionRegistry registry = new SqmFunctionRegistry();
		typeConfiguration.scope(
				new DialectFeatureChecks.FakeMetadataBuildingContext( typeConfiguration, registry ) );
		H2Dialect dialect = new H2Dialect();
		FunctionContributions contributions =
				new DialectFeatureChecks.FakeFunctionContributions( dialect, typeConfiguration, registry );
		dialect.initializeFunctionRegistry( contributions );
		bh.consume( registry.getValidFunctionKeys().size() );
		return registry;
	}

	@Benchmark
	public int lookupAfterRegistration_postgresql(Blackhole bh) {
		TypeConfiguration typeConfiguration = new TypeConfiguration();
		SqmFunctionRegistry registry = new SqmFunctionRegistry();
		typeConfiguration.scope(
				new DialectFeatureChecks.FakeMetadataBuildingContext( typeConfiguration, registry ) );
		PostgreSQLDialect dialect = new PostgreSQLDialect();
		FunctionContributions contributions =
				new DialectFeatureChecks.FakeFunctionContributions( dialect, typeConfiguration, registry );
		dialect.initializeFunctionRegistry( contributions );

		// Simulate named query validation looking up 5 functions
		bh.consume( registry.findFunctionDescriptor( "count" ) );
		bh.consume( registry.findFunctionDescriptor( "sum" ) );
		bh.consume( registry.findFunctionDescriptor( "lower" ) );
		bh.consume( registry.findFunctionDescriptor( "cast" ) );
		bh.consume( registry.findFunctionDescriptor( "coalesce" ) );
		return registry.getValidFunctionKeys().size();
	}
}
