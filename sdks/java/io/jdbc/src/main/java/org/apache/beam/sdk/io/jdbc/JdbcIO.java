/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.jdbc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Random;

import javax.annotation.Nullable;
import javax.sql.DataSource;

import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.apache.commons.dbcp2.BasicDataSource;

/**
 * IO to read and write data on JDBC.
 *
 * <h3>Reading from JDBC datasource</h3>
 *
 * <p>JdbcIO source returns a bounded collection of {@code T} as a {@code PCollection<T>}. T is the
 * type returned by the provided {@link RowMapper}.
 *
 * <p>To configure the JDBC source, you have to provide a {@link DataSourceConfiguration} using
 * {@link DataSourceConfiguration#create} with either a {@link DataSource} (which must be
 * {@link Serializable}) or the parameters needed to create it (driver class name, url, and
 * optionally username and password). For example:
 *
 * <pre>{@code
 * pipeline.apply(JdbcIO.<KV<Integer, String>>read()
 *   .withDataSourceConfiguration(JdbcIO.DataSourceConfiguration.create(
 *       "com.mysql.jdbc.Driver", "jdbc:mysql://hostname:3306/mydb",
 *       "username", "password"))
 *   .withQuery("select id,name from Person")
 *   .withRowMapper(new JdbcIO.RowMapper<KV<Integer, String>>() {
 *     public KV<Integer, String> mapRow(ResultSet resultSet) throws Exception {
 *       return KV.of(resultSet.getInt(1), resultSet.getString(2));
 *     }
 *   })
 * }</pre>
 *
 * <h3>Writing to JDBC datasource</h3>
 *
 * <p>JDBC sink supports writing records into a database. It writes a {@link PCollection} to the
 * database by converting each T into a {@link PreparedStatement} via a user-provided {@link
 * PreparedStatementSetter}.
 *
 * <p>Like the source, to configure the sink, you have to provide a {@link DataSourceConfiguration}.
 *
 * <pre>{@code
 * pipeline
 *   .apply(...)
 *   .apply(JdbcIO.<KV<Integer, String>>write()
 *      .withDataSourceConfiguration(JdbcIO.DataSourceConfiguration.create(
 *         "com.mysql.jdbc.Driver", "jdbc:mysql://hostname:3306/mydb",
 *         "username", "password"))
 *      .withStatement("insert into Person values(?, ?)")
 *      .withPreparedStatementSetter(new JdbcIO.PreparedStatementSetter<KV<Integer, String>>() {
 *        public void setParameters(KV<Integer, String> element, PreparedStatement query) {
 *          query.setInt(1, kv.getKey());
 *          query.setString(2, kv.getValue());
 *        }
 *      })
 * }</pre>
 *
 * <p>NB: in case of transient failures, Beam runners may execute parts of JdbcIO.Write multiple
 * times for fault tolerance. Because of that, you should avoid using {@code INSERT} statements,
 * since that risks duplicating records in the database, or failing due to primary key conflicts.
 * Consider using <a href="https://en.wikipedia.org/wiki/Merge_(SQL)">MERGE ("upsert")
 * statements</a> supported by your database instead.
 */
public class JdbcIO {
  /**
   * Read data from a JDBC datasource.
   *
   * @param <T> Type of the data to be read.
   */
  public static <T> Read<T> read() {
    return new AutoValue_JdbcIO_Read.Builder<T>().build();
  }

  /**
   * Write data to a JDBC datasource.
   *
   * @param <T> Type of the data to be written.
   */
  public static <T> Write<T> write() {
    return new AutoValue_JdbcIO_Write.Builder<T>().build();
  }

  private JdbcIO() {}

  /**
   * An interface used by {@link JdbcIO.Read} for converting each row of the {@link ResultSet} into
   * an element of the resulting {@link PCollection}.
   */
  public interface RowMapper<T> extends Serializable {
    T mapRow(ResultSet resultSet) throws Exception;
  }

  /**
   * A POJO describing a {@link DataSource}, either providing directly a {@link DataSource} or all
   * properties allowing to create a {@link DataSource}.
   */
  @AutoValue
  abstract static class DataSourceConfiguration implements Serializable {
    @Nullable abstract String getDriverClassName();
    @Nullable abstract String getUrl();
    @Nullable abstract String getUsername();
    @Nullable abstract String getPassword();
    @Nullable abstract DataSource getDataSource();

    /** Configuration using a {@link Serializable} {@link DataSource}. */
    public static DataSourceConfiguration create(DataSource dataSource) {
      checkNotNull(dataSource, "dataSource");
      checkArgument(dataSource instanceof Serializable, "dataSource must be Serializable");
      return new AutoValue_JdbcIO_DataSourceConfiguration(null, null, null, null, dataSource);
    }

    /** Configuration using the given driver, url, username and password. */
    public static DataSourceConfiguration create(
        String driverClassName, String url, String username, String password) {
      checkNotNull(driverClassName, "driverClassName");
      checkNotNull(url, "url");
      checkNotNull(username, "username");
      checkNotNull(password, "password");
      return new AutoValue_JdbcIO_DataSourceConfiguration(
          driverClassName, url, username, password, null);
    }

    /** Configuration using the given driver and url, without a username and password. */
    public static DataSourceConfiguration create(String driverClassName, String url) {
      checkNotNull(driverClassName, "driverClassName");
      checkNotNull(url, "url");
      return new AutoValue_JdbcIO_DataSourceConfiguration(driverClassName, url, null, null, null);
    }

    private void populateDisplayData(DisplayData.Builder builder) {
      if (getDataSource() != null) {
        builder.addIfNotNull(DisplayData.item("dataSource", getDataSource().getClass().getName()));
      } else {
        builder.addIfNotNull(DisplayData.item("jdbcDriverClassName", getDriverClassName()));
        builder.addIfNotNull(DisplayData.item("jdbcUrl", getUrl()));
        builder.addIfNotNull(DisplayData.item("username", getUsername()));
      }
    }

    Connection getConnection() throws Exception {
      DataSource dataSource;
      if (getDataSource() != null) {
        dataSource = getDataSource();
      } else {
        BasicDataSource basicDataSource = new BasicDataSource();
        basicDataSource.setDriverClassName(getDriverClassName());
        basicDataSource.setUrl(getUrl());
        basicDataSource.setUsername(getUsername());
        basicDataSource.setPassword(getPassword());
        dataSource = basicDataSource;
      }
      return (getUsername() == null)
          ? dataSource.getConnection()
          : dataSource.getConnection(getUsername(), getPassword());
    }
  }

  /** A {@link PTransform} to read data from a JDBC datasource. */
  @AutoValue
  public abstract static class Read<T> extends PTransform<PBegin, PCollection<T>> {
    @Nullable abstract DataSourceConfiguration getDataSourceConfiguration();
    @Nullable abstract String getQuery();
    @Nullable abstract RowMapper<T> getRowMapper();
    @Nullable abstract Coder<T> getCoder();

    abstract Builder<T> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> setDataSourceConfiguration(DataSourceConfiguration config);
      abstract Builder<T> setQuery(String query);
      abstract Builder<T> setRowMapper(RowMapper<T> rowMapper);
      abstract Builder<T> setCoder(Coder<T> coder);
      abstract Read<T> build();
    }

    public Read<T> withDataSourceConfiguration(DataSourceConfiguration configuration) {
      checkNotNull(configuration, "configuration");
      return toBuilder().setDataSourceConfiguration(configuration).build();
    }

    public Read<T> withQuery(String query) {
      checkNotNull(query, "query");
      return toBuilder().setQuery(query).build();
    }

    public Read<T> withRowMapper(RowMapper<T> rowMapper) {
      checkNotNull(rowMapper, "rowMapper");
      return toBuilder().setRowMapper(rowMapper).build();
    }

    public Read<T> withCoder(Coder<T> coder) {
      checkNotNull(coder, "coder");
      return toBuilder().setCoder(coder).build();
    }

    @Override
    public PCollection<T> apply(PBegin input) {
      return input
          .apply(Create.of(getQuery()))
          .apply(ParDo.of(new ReadFn<>(this))).setCoder(getCoder())
          // generate a random key followed by a GroupByKey and then ungroup
          // to prevent fusion
          // see https://cloud.google.com/dataflow/service/dataflow-service-desc#preventing-fusion
          // for details
          .apply(ParDo.of(new DoFn<T, KV<Integer, T>>() {
            private Random random;
            @Setup
            public void setup() {
              random = new Random();
            }
            @ProcessElement
            public void processElement(ProcessContext context) {
              context.output(KV.of(random.nextInt(), context.element()));
            }
          }))
          .apply(GroupByKey.<Integer, T>create())
          .apply(Values.<Iterable<T>>create())
          .apply(Flatten.<T>iterables());
    }

    @Override
    public void validate(PBegin input) {
      checkNotNull(getQuery(), "query");
      checkNotNull(getRowMapper(), "rowMapper");
      checkNotNull(getCoder(), "coder");
      checkNotNull(getDataSourceConfiguration());
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      builder.add(DisplayData.item("query", getQuery()));
      builder.add(DisplayData.item("rowMapper", getRowMapper().getClass().getName()));
      builder.add(DisplayData.item("coder", getCoder().getClass().getName()));
      getDataSourceConfiguration().populateDisplayData(builder);
    }

    /** A {@link DoFn} executing the SQL query to read from the database. */
    static class ReadFn<T> extends DoFn<String, T> {
      private JdbcIO.Read<T> spec;
      private Connection connection;

      private ReadFn(Read<T> spec) {
        this.spec = spec;
      }

      @Setup
      public void setup() throws Exception {
        connection = spec.getDataSourceConfiguration().getConnection();
      }

      @ProcessElement
      public void processElement(ProcessContext context) throws Exception {
        String query = context.element();
        try (PreparedStatement statement = connection.prepareStatement(query)) {
          try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
              context.output(spec.getRowMapper().mapRow(resultSet));
            }
          }
        }
      }

      @Teardown
      public void teardown() throws Exception {
        if (connection != null) {
          connection.close();
        }
      }
    }
  }

  /**
   * An interface used by the JdbcIO Write to set the parameters of the {@link PreparedStatement}
   * used to setParameters into the database.
   */
  public interface PreparedStatementSetter<T> extends Serializable {
    void setParameters(T element, PreparedStatement preparedStatement) throws Exception;
  }

  /** A {@link PTransform} to write to a JDBC datasource. */
  @AutoValue
  public abstract static class Write<T> extends PTransform<PCollection<T>, PDone> {
    @Nullable abstract DataSourceConfiguration getDataSourceConfiguration();
    @Nullable abstract String getStatement();
    @Nullable abstract PreparedStatementSetter<T> getPreparedStatementSetter();

    abstract Builder<T> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> setDataSourceConfiguration(DataSourceConfiguration config);
      abstract Builder<T> setStatement(String statement);
      abstract Builder<T> setPreparedStatementSetter(PreparedStatementSetter<T> setter);

      abstract Write<T> build();
    }

    public Write<T> withDataSourceConfiguration(DataSourceConfiguration config) {
      return toBuilder().setDataSourceConfiguration(config).build();
    }
    public Write<T> withStatement(String statement) {
      return toBuilder().setStatement(statement).build();
    }
    public Write<T> withPreparedStatementSetter(PreparedStatementSetter<T> setter) {
      return toBuilder().setPreparedStatementSetter(setter).build();
    }

    @Override
    public PDone apply(PCollection<T> input) {
      input.apply(ParDo.of(new WriteFn<T>(this)));
      return PDone.in(input.getPipeline());
    }

    @Override
    public void validate(PCollection<T> input) {
      checkNotNull(getDataSourceConfiguration(), "dataSourceConfiguration");
      checkNotNull(getStatement(), "statement");
      checkNotNull(getPreparedStatementSetter(), "preparedStatementSetter");
    }

    private static class WriteFn<T> extends DoFn<T, Void> {
      private static final int DEFAULT_BATCH_SIZE = 1000;

      private final Write<T> spec;

      private Connection connection;
      private PreparedStatement preparedStatement;
      private int batchCount;

      public WriteFn(Write<T> spec) {
        this.spec = spec;
      }

      @Setup
      public void setup() throws Exception {
        connection = spec.getDataSourceConfiguration().getConnection();
        connection.setAutoCommit(false);
        preparedStatement = connection.prepareStatement(spec.getStatement());
      }

      @StartBundle
      public void startBundle(Context context) {
        batchCount = 0;
      }

      @ProcessElement
      public void processElement(ProcessContext context) throws Exception {
        T record = context.element();

        preparedStatement.clearParameters();
        spec.getPreparedStatementSetter().setParameters(record, preparedStatement);
        preparedStatement.addBatch();

        batchCount++;

        if (batchCount >= DEFAULT_BATCH_SIZE) {
          finishBundle(context);
        }
      }

      @FinishBundle
      public void finishBundle(Context context) throws Exception {
        if (batchCount > 0) {
          preparedStatement.executeBatch();
          connection.commit();
          batchCount = 0;
        }
      }

      @Teardown
      public void teardown() throws Exception {
        try {
          if (preparedStatement != null) {
            preparedStatement.close();
          }
        } finally {
          if (connection != null) {
            connection.close();
          }
        }
      }
    }
  }
}