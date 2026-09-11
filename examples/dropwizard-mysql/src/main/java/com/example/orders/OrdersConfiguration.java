package com.example.orders;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class OrdersConfiguration extends Configuration {
  @Valid @NotNull private SkipperFactory skipper = new SkipperFactory();

  /** Required when skipper.store is mysql; unused for sqlite. */
  @Valid private DataSourceFactory database;

  @JsonProperty
  public SkipperFactory getSkipper() {
    return skipper;
  }

  @JsonProperty
  public void setSkipper(SkipperFactory skipper) {
    this.skipper = skipper;
  }

  @JsonProperty
  public DataSourceFactory getDatabase() {
    return database;
  }

  @JsonProperty
  public void setDatabase(DataSourceFactory database) {
    this.database = database;
  }

  /** Which Skipper store to run on. */
  public static class SkipperFactory {
    @NotNull @JsonProperty public String store = "mysql";
    @JsonProperty public String sqlitePath = "orders.db";
  }
}
