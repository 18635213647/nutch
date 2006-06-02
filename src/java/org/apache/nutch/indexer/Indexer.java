/**
 * Copyright 2005 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nutch.indexer;

import java.io.*;
import java.util.*;
import java.util.logging.*;

import org.apache.hadoop.io.*;
import org.apache.nutch.fetcher.Fetcher;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.util.LogFormatter;
import org.apache.hadoop.mapred.*;
import org.apache.nutch.parse.*;
import org.apache.nutch.analysis.*;

import org.apache.nutch.scoring.ScoringFilterException;
import org.apache.nutch.scoring.ScoringFilters;
import org.apache.nutch.util.NutchConfiguration;
import org.apache.nutch.util.NutchJob;

import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.Inlinks;
import org.apache.nutch.crawl.LinkDb;

import org.apache.lucene.index.*;
import org.apache.lucene.document.*;
import org.apache.nutch.metadata.Metadata;

/** Create indexes for segments. */
public class Indexer extends Configured implements Reducer {
  
  public static final String DONE_NAME = "index.done";

  public static final Logger LOG =
    LogFormatter.getLogger("org.apache.nutch.crawl.Indexer");

  /** Wraps inputs in an {@link ObjectWritable}, to permit merging different
   * types in reduce. */
  public static class InputFormat extends SequenceFileInputFormat {
    public RecordReader getRecordReader(FileSystem fs, FileSplit split,
                                        JobConf job, Reporter reporter)
      throws IOException {

      reporter.setStatus(split.toString());
      
      return new SequenceFileRecordReader(job, split) {
          public synchronized boolean next(Writable key, Writable value)
            throws IOException {
            ObjectWritable wrapper = (ObjectWritable)value;
            try {
              wrapper.set(getValueClass().newInstance());
            } catch (Exception e) {
              throw new IOException(e.toString());
            }
            return super.next(key, (Writable)wrapper.get());
          }
        };
    }
  }

  /** Unwrap Lucene Documents created by reduce and add them to an index. */
  public static class OutputFormat
    extends org.apache.hadoop.mapred.OutputFormatBase {
    public RecordWriter getRecordWriter(final FileSystem fs, JobConf job,
                                        String name) throws IOException {
      final Path perm = new Path(job.getOutputPath(), name);
      final Path temp =
        job.getLocalPath("index/_"+Integer.toString(new Random().nextInt()));

      fs.delete(perm);                            // delete old, if any

      final AnalyzerFactory factory = new AnalyzerFactory(job);
      final IndexWriter writer =                  // build locally first
        new IndexWriter(fs.startLocalOutput(perm, temp).toString(),
                        new NutchDocumentAnalyzer(job), true);

      writer.setMergeFactor(job.getInt("indexer.mergeFactor", 10));
      writer.setMaxBufferedDocs(job.getInt("indexer.minMergeDocs", 100));
      writer.setMaxMergeDocs(job.getInt("indexer.maxMergeDocs", Integer.MAX_VALUE));
      writer.setTermIndexInterval
        (job.getInt("indexer.termIndexInterval", 128));
      writer.setMaxFieldLength(job.getInt("indexer.max.tokens", 10000));
      writer.setInfoStream(LogFormatter.getLogStream(LOG, Level.INFO));
      writer.setUseCompoundFile(false);
      writer.setSimilarity(new NutchSimilarity());

      return new RecordWriter() {
          boolean closed;

          public void write(WritableComparable key, Writable value)
            throws IOException {                  // unwrap & index doc
            Document doc = (Document)((ObjectWritable)value).get();
            NutchAnalyzer analyzer = factory.get(doc.get("lang"));
            LOG.info(" Indexing [" + doc.getField("url").stringValue() + "]" +
                     " with analyzer " + analyzer +
                     " (" + doc.get("lang") + ")");
            writer.addDocument(doc, analyzer);
          }
          
          public void close(final Reporter reporter) throws IOException {
            // spawn a thread to give progress heartbeats
            Thread prog = new Thread() {
                public void run() {
                  while (!closed) {
                    try {
                      reporter.setStatus("closing");
                      Thread.sleep(1000);
                    } catch (InterruptedException e) { continue; }
                      catch (Throwable e) { return; }
                  }
                }
              };

            try {
              prog.start();
              LOG.info("Optimizing index.");        // optimize & close index
              writer.optimize();
              writer.close();
              fs.completeLocalOutput(perm, temp);   // copy to dfs
              fs.createNewFile(new Path(perm, DONE_NAME));
            } finally {
              closed = true;
            }
          }
        };
    }
  }

  private IndexingFilters filters;
  private ScoringFilters scfilters;

  public Indexer() {
    super(null);
  }

  /** Construct an Indexer. */
  public Indexer(Configuration conf) {
    super(conf);
  }

  public void configure(JobConf job) {
    setConf(job);
    this.filters = new IndexingFilters(getConf());
    this.scfilters = new ScoringFilters(getConf());
  }

  public void close() {}

  public void reduce(WritableComparable key, Iterator values,
                     OutputCollector output, Reporter reporter)
    throws IOException {
    Inlinks inlinks = null;
    CrawlDatum dbDatum = null;
    CrawlDatum fetchDatum = null;
    ParseData parseData = null;
    ParseText parseText = null;
    while (values.hasNext()) {
      Object value = ((ObjectWritable)values.next()).get(); // unwrap
      if (value instanceof Inlinks) {
        inlinks = (Inlinks)value;
      } else if (value instanceof CrawlDatum) {
        CrawlDatum datum = (CrawlDatum)value;
        switch (datum.getStatus()) {
        case CrawlDatum.STATUS_DB_UNFETCHED:
        case CrawlDatum.STATUS_DB_FETCHED:
        case CrawlDatum.STATUS_DB_GONE:
          dbDatum = datum;
          break;
        case CrawlDatum.STATUS_FETCH_SUCCESS:
        case CrawlDatum.STATUS_FETCH_RETRY:
        case CrawlDatum.STATUS_FETCH_GONE:
          fetchDatum = datum;
          break;
        default:
          throw new RuntimeException("Unexpected status: "+datum.getStatus());
        }
      } else if (value instanceof ParseData) {
        parseData = (ParseData)value;
      } else if (value instanceof ParseText) {
        parseText = (ParseText)value;
      } else {
        LOG.warning("Unrecognized type: "+value.getClass());
      }
    }      

    if (fetchDatum == null || dbDatum == null
        || parseText == null || parseData == null) {
      return;                                     // only have inlinks
    }

    Document doc = new Document();
    Metadata metadata = parseData.getContentMeta();

    // add segment, used to map from merged index back to segment files
    doc.add(new Field("segment", metadata.get(Fetcher.SEGMENT_NAME_KEY),
            Field.Store.YES, Field.Index.NO));

    // add digest, used by dedup
    doc.add(new Field("digest", metadata.get(Fetcher.SIGNATURE_KEY),
            Field.Store.YES, Field.Index.NO));

//     LOG.info("Url: "+key.toString());
//     LOG.info("Title: "+parseData.getTitle());
//     LOG.info(crawlDatum.toString());
//     if (inlinks != null) {
//       LOG.info(inlinks.toString());
//     }

    Parse parse = new ParseImpl(parseText, parseData);
    try {
      // run indexing filters
      doc = this.filters.filter(doc, parse, (UTF8)key, fetchDatum, inlinks);
    } catch (IndexingException e) {
      LOG.warning("Error indexing "+key+": "+e);
      return;
    }

    float boost = 1.0f;
    // run scoring filters
    try {
      boost = this.scfilters.indexerScore((UTF8)key, doc, dbDatum,
              fetchDatum, parse, inlinks, boost);
    } catch (ScoringFilterException e) {
      LOG.warning("Error calculating score " + key + ": " + e);
      return;
    }
    // apply boost to all indexed fields.
    doc.setBoost(boost);
    // store boost for use by explain and dedup
    doc.add(new Field("boost", Float.toString(boost),
            Field.Store.YES, Field.Index.NO));

    output.collect(key, new ObjectWritable(doc));
  }

  public void index(Path indexDir, Path crawlDb, Path linkDb, Path[] segments)
    throws IOException {

    LOG.info("Indexer: starting");
    LOG.info("Indexer: linkdb: " + linkDb);

    JobConf job = new NutchJob(getConf());
    job.setJobName("index " + indexDir);

    for (int i = 0; i < segments.length; i++) {
      LOG.info("Indexer: adding segment: " + segments[i]);
      job.addInputPath(new Path(segments[i], CrawlDatum.FETCH_DIR_NAME));
      job.addInputPath(new Path(segments[i], ParseData.DIR_NAME));
      job.addInputPath(new Path(segments[i], ParseText.DIR_NAME));
    }

    job.addInputPath(new Path(crawlDb, CrawlDatum.DB_DIR_NAME));
    job.addInputPath(new Path(linkDb, LinkDb.CURRENT_NAME));

    job.setInputFormat(InputFormat.class);
    job.setInputKeyClass(UTF8.class);
    job.setInputValueClass(ObjectWritable.class);

    //job.setCombinerClass(Indexer.class);
    job.setReducerClass(Indexer.class);

    job.setOutputPath(indexDir);
    job.setOutputFormat(OutputFormat.class);
    job.setOutputKeyClass(UTF8.class);
    job.setOutputValueClass(ObjectWritable.class);

    JobClient.runJob(job);
    LOG.info("Indexer: done");
  }

  public static void main(String[] args) throws Exception {
    Indexer indexer = new Indexer(NutchConfiguration.create());
    
    if (args.length < 4) {
      System.err.println("Usage: <index> <crawldb> <linkdb> <segment> ...");
      return;
    }
    
    Path[] segments = new Path[args.length-3];
    for (int i = 3; i < args.length; i++) {
      segments[i-3] = new Path(args[i]);
    }

    indexer.index(new Path(args[0]), new Path(args[1]), new Path(args[2]),
                  segments);
  }

}
