package avrobase.solr;

import avrobase.AvroBaseException;
import avrobase.AvroBaseImpl;
import avrobase.AvroFormat;
import avrobase.Row;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.commons.lang.NotImplementedException;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Implement search via Solr. Extend this class instead of the AvroBaseImpl and use the index methods
 * upon successful insertions into your avrobase.
 * <p/>
 * User: sam
 * Date: Jun 27, 2010
 * Time: 10:50:31 AM
 */
public abstract class SolrAvroBase<T extends SpecificRecord, K> extends AvroBaseImpl<T, K> {
  private static final String SCHEMA_LOCATION = "/admin/file/?file=schema.xml";
  protected SolrServer solrServer;
  protected String uniqueKey;
  protected List<String> fields;

  // TODO: Replace this with a better way to commit / optimize
  private volatile long lastCommit = System.currentTimeMillis();
  private volatile long lastOptimize = System.currentTimeMillis();
  private static Timer commitTimer = new Timer();

  /**
   * Given a solr url this constructor will pull the schema and use that to index
   * values when the index function is called. The search method then can query the
   * indexed objects and return them. The Solr configuration is the single source
   * of configuration for which fields are indexed.  The uniquekey has to be a string
   * converted via utf-8 from the row id.
   * @param format
   * @param solrURL
   * @throws avrobase.AvroBaseException
   */
  public SolrAvroBase(AvroFormat format, String solrURL) throws AvroBaseException {
    super(format);
    if (solrURL != null) {
      try {
        solrServer = new CommonsHttpSolrServer(solrURL);
        URL url = new URL(solrURL + SolrAvroBase.SCHEMA_LOCATION);
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document document = db.parse(url.openStream());

        // Need to get the unique key and all the fields
        NodeList uniqueKeys = document.getElementsByTagName("uniqueKey");
        if (uniqueKeys == null || uniqueKeys.getLength() != 1) {
          throw new AvroBaseException("Invalid schema configuration, must have 1 unique key");
        }
        uniqueKey = uniqueKeys.item(0).getTextContent();

        // Now get all the fields we are going to index and query
        NodeList fieldList = document.getElementsByTagName("field");
        fields = new ArrayList<String>(fieldList.getLength());
        for (int i = 0; i < fieldList.getLength(); i++) {
          Node field = fieldList.item(i);
          String name = field.getAttributes().getNamedItem("name").getTextContent();
          fields.add(name);
        }
      } catch (MalformedURLException e) {
        throw new AvroBaseException("Invalid Solr URL: " + solrURL, e);
      } catch (ParserConfigurationException e) {
        throw new AvroBaseException(e);
      } catch (SAXException e) {
        throw new AvroBaseException("Failed to parse schema", e);
      } catch (IOException e) {
        throw new AvroBaseException("Failed to read schema", e);
      }
    }
  }

  /**
   * Query the solr instance and return matching documents in score order. Should we
   * return only stored fields or load them automatically?
   * @param query
   * @param start
   * @param rows
   * @return
   * @throws avrobase.AvroBaseException
   */
  @Override
  public Iterable<Row<T, K>> search(String query, int start, int rows) throws AvroBaseException {
    if (solrServer == null) {
      throw new AvroBaseException("Searching for this type is not enabled");
    }
    SolrQuery solrQuery = new SolrQuery().setQuery(query).setStart(start).setRows(rows).setFields(uniqueKey);
    try {
      QueryResponse queryResponse = solrServer.query(solrQuery);
      SolrDocumentList list = queryResponse.getResults();
      final Iterator<SolrDocument> solrDocumentIterator = list.iterator();
      return new Iterable<Row<T, K>>() {

        @Override
        public Iterator<Row<T, K>> iterator() {
          return new Iterator<Row<T, K>>() {

            @Override
            public boolean hasNext() {
              return solrDocumentIterator.hasNext();
            }

            @Override
            public Row<T, K> next() {
              SolrDocument solrDocument = solrDocumentIterator.next();
              Map<String, Object> map = solrDocument.getFieldValueMap();
              Object o = map.get(uniqueKey);
              if (o == null) {
                throw new AvroBaseException("Unique key not present in document");
              }
              return get($(o.toString()));
            }

            @Override
            public void remove() {
              throw new NotImplementedException();
            }
          };
        }
      };
    } catch (SolrServerException e) {
      throw new AvroBaseException("Query failure: " + query, e);
    }
  }

  /**
   * Remove an id from the index.
   * @param row
   * @throws AvroBaseException
   */
  protected void unindex(K row) throws AvroBaseException {
    if (solrServer == null) {
      return;
    }
    try {
      solrServer.deleteById($_(row));
      solrServer.commit();
    } catch (SolrServerException e) {
      throw new AvroBaseException(e);
    } catch (IOException e) {
      throw new AvroBaseException(e);
    }
  }

  /**
   * Index a row and value.
   * @param row
   * @param value
   * @return
   */
  protected boolean index(K row, T value) throws AvroBaseException {
    if (solrServer == null) {
      return false;
    }
    Schema schema = value.getSchema();
    SolrInputDocument document = new SolrInputDocument();
    for (String field : fields) {
      Schema.Field f = schema.getField(field);
      if (f != null) {
        Object o = value.get(f.pos());
        document.addField(field, o);
      }
    }
    document.addField(uniqueKey, $_(row));
    try {
      UpdateRequest req = new UpdateRequest();
      long current = System.currentTimeMillis();
      if (current - lastCommit > 100) {
        lastCommit = current;
        req.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, false);
      } else {
        final long oldLastCommit = lastCommit;
        commitTimer.schedule(new TimerTask() {
          @Override
          public void run() {
            // TODO: this needs to be tested to make sure it is committing the last tx
            if (oldLastCommit == lastCommit) {
              lastCommit = System.currentTimeMillis();
              UpdateRequest req = new UpdateRequest();
              req.setAction(AbstractUpdateRequest.ACTION.COMMIT, false, false);
              try {
                solrServer.request(req);
              } catch (Exception e) {
                e.printStackTrace();
              }
            }
          }
        }, current + 100);
      }
      if (current - lastOptimize > 3600) {
        lastOptimize = current;
        req.setAction(AbstractUpdateRequest.ACTION.OPTIMIZE, false, false);
      }
      req.add(document);
      solrServer.request(req);
      return true;
    } catch (SolrServerException e) {
      throw new AvroBaseException(e);
    } catch (IOException e) {
      throw new AvroBaseException(e);
    }
  }

  /**
   * Reindex all rows.  Could be very very expensive.
   */
  protected void reindex() {
    for (Row<T, K> tRow: scan(null, null)) {
      index(tRow.row, tRow.value);
    }
  }
}
