/**
 * Copyright (C) 2020 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.expediagroup.hiveberg;

import com.google.common.collect.Lists;
import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.StandaloneHiveRunner;
import com.klarna.hiverunner.annotations.HiveSQL;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.hadoop.HadoopCatalog;
import org.apache.iceberg.types.Types;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

@RunWith(StandaloneHiveRunner.class)
public class TestInputFormatWithHadoopCatalog {

  @HiveSQL(files = {}, autoStart = true)
  private HiveShell shell;

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private File tableLocation;
  private IcebergInputFormat format = new IcebergInputFormat();
  private JobConf conf = new JobConf();

  @Before
  public void before() throws IOException {
    tableLocation = temp.newFolder();
    Schema schema = new Schema(required(1, "id", Types.LongType.get()),
        optional(2, "data", Types.StringType.get()));
    PartitionSpec spec = PartitionSpec.unpartitioned();

    Configuration conf = new Configuration();
    HadoopCatalog catalog = new HadoopCatalog(conf, tableLocation.getAbsolutePath());
    TableIdentifier id = TableIdentifier.parse("source_db.table_a");
    Table table = catalog.createTable(id, schema, spec);

    List<Record> data = new ArrayList<>();
    data.add(TestHelpers.createSimpleRecord(1L, "Michael"));
    data.add(TestHelpers.createSimpleRecord(2L, "Andy"));
    data.add(TestHelpers.createSimpleRecord(3L, "Berta"));

    DataFile fileA = TestHelpers.writeFile(temp.newFile(), table, null, FileFormat.PARQUET, data);

    table.newAppend().appendFile(fileA).commit();
  }

  @Test
  public void testStorageHandler() {
    shell.execute("CREATE DATABASE source_db");
    shell.execute(new StringBuilder()
        .append("CREATE TABLE source_db.table_a ")
        .append("STORED BY 'com.expediagroup.hiveberg.IcebergStorageHandler' ")
        .append("LOCATION '")
        .append(tableLocation.getAbsolutePath() + "/source_db/table_a")
        .append("' TBLPROPERTIES ('iceberg.catalog'='hadoop.catalog', 'iceberg.warehouse.location'='")
        .append(tableLocation.getAbsolutePath())
        .append("')")
        .toString());

    List<Object[]> result = shell.executeStatement("SELECT id, data FROM source_db.table_a");

    assertEquals(3, result.size());
    assertArrayEquals(new Object[]{1L, "Michael"}, result.get(0));
    assertArrayEquals(new Object[]{2L, "Andy"}, result.get(1));
    assertArrayEquals(new Object[]{3L, "Berta"}, result.get(2));
  }

  @Test
  public void testInputFormat () {
    shell.execute("CREATE DATABASE source_db");
    shell.execute(new StringBuilder()
        .append("CREATE TABLE source_db.table_a ")
        .append("ROW FORMAT SERDE 'com.expediagroup.hiveberg.IcebergSerDe' ")
        .append("STORED AS ")
        .append("INPUTFORMAT 'com.expediagroup.hiveberg.IcebergInputFormat' ")
        .append("OUTPUTFORMAT 'org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat' ")
        .append("LOCATION '")
        .append(tableLocation.getAbsolutePath() + "/source_db/table_a")
        .append("' TBLPROPERTIES ('iceberg.catalog'='hadoop.catalog', 'iceberg.warehouse.location'='")
        .append(tableLocation.getAbsolutePath())
        .append("')")
        .toString());

    List<Object[]> result = shell.executeStatement("SELECT id, data FROM source_db.table_a");

    assertEquals(3, result.size());
    assertArrayEquals(new Object[]{1L, "Michael"}, result.get(0));
    assertArrayEquals(new Object[]{2L, "Andy"}, result.get(1));
    assertArrayEquals(new Object[]{3L, "Berta"}, result.get(2));
  }

  @Test
  public void testGetSplits() throws IOException {
    IcebergInputFormat format = new IcebergInputFormat();
    JobConf conf = new JobConf();
    conf.set("location", tableLocation.getAbsolutePath() + "/source_db/table_a");
    conf.set("iceberg.warehouse.location", tableLocation.getAbsolutePath());
    conf.set("iceberg.catalog", "hadoop.catalog");
    conf.set("name", "source_db.table_a");
    InputSplit[] splits = format.getSplits(conf, 1);
    assertEquals(splits.length, 1);
  }

  @Test
  public void testGetRecordReader() throws IOException {
    IcebergInputFormat format = new IcebergInputFormat();
    JobConf conf = new JobConf();
    conf.set("location", tableLocation.getAbsolutePath() + "/source_db/table_a");
    conf.set("iceberg.warehouse.location", tableLocation.getAbsolutePath());
    conf.set("iceberg.catalog", "hadoop.catalog");
    conf.set("name", "source_db.table_a");
    InputSplit[] splits = format.getSplits(conf, 1);
    RecordReader reader = format.getRecordReader(splits[0], conf, null);
    IcebergWritable value = (IcebergWritable) reader.createValue();

    List<Record> records = Lists.newArrayList();
    boolean unfinished = true;
    while(unfinished) {
      if (reader.next(null, value)) {
        records.add(value.getRecord().copy());
      } else  {
        unfinished = false;
      }
    }
    assertEquals(3, records.size() );
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetSplitsNoWarehouseLocation() throws IOException {
    conf.set("iceberg.catalog", "hadoop.catalog");
    conf.set("location", "file:" + tableLocation);
    conf.set("name", "source_db.table_a");
    format.getSplits(conf, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetSplitsNoLocation() throws IOException {
    conf.set("iceberg.catalog", "hadoop.catalog");
    conf.set("iceberg.warehouse.location", "file:" + tableLocation);
    conf.set("name", "source_db.table_a");
    format.getSplits(conf, 1);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testGetSplitsNoCatalog() throws IOException {
    conf.set("iceberg.warehouse.location", "file:" + tableLocation);
    conf.set("location", "file:" + tableLocation);
    conf.set("name", "source_db.table_a");
    format.getSplits(conf, 1);
  }

  @Test(expected = IOException.class)
  public void testGetSplitsInvalidWarehouseLocationUri() throws IOException {
    conf.set("iceberg.warehouse.location", "http:");
    conf.set("iceberg.catalog", "hadoop.catalog");
    conf.set("location", "file:" + tableLocation);
    conf.set("name", "source_db.table_a");
    format.getSplits(conf, 1);
  }

  @After
  public void after() throws IOException {
    FileUtils.deleteDirectory(tableLocation);
  }
}
