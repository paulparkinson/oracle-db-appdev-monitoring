package oracle.observability.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.prometheus.client.Gauge;
import oracle.observability.ObservabilityExporter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@RestController
public class DBMetricsExporter extends ObservabilityExporter {

    public String LISTEN_ADDRESS      = System.getenv("LISTEN_ADDRESS"); // ":9161"
    public String TELEMETRY_PATH         = System.getenv("TELEMETRY_PATH"); // "/metrics"
    //Interval between each scrape. Default is to scrape on collect requests. scrape.interval
    public String SCRAPE_INTERVAL     = System.getenv("scrape.interval"); // "0s"
    public static final String ORACLEDB_METRIC_PREFIX = "oracledb_";
    Map<String, Gauge> gaugeMap = new HashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger(DBMetricsExporter.class);

    /**
     * The endpoint that prometheus will scrape
     * @return Prometheus metric
     * @throws Exception
     */
    @GetMapping(value = "/metrics", produces = "text/plain")
    public String metrics() throws Exception {
        processMetrics();
        return MetricsHandler.getMetricsString();
    }

    @PostConstruct
    public void init() throws Exception {
        processMetrics();
    }

    private void processMetrics() throws IOException {
        LOG.debug("Successfully loaded default metrics from:" + DEFAULT_METRICS);
        LOG.debug("OracleDBMetricsExporter CUSTOM_METRICS:" + CUSTOM_METRICS); //todo only default metrics are processed currently
        File tomlfile = new File(DEFAULT_METRICS);
        TomlMapper mapper = new TomlMapper();
        JsonNode jsonNode = mapper.readerFor(MetricEntry.class).readTree(new FileInputStream(tomlfile));
        Iterator<JsonNode> metric = jsonNode.get("metric").iterator();
        int isConnectionSuccessful = 0;
        try(Connection connection = getPoolDataSource().getConnection()) {
            isConnectionSuccessful = 1;
            while (metric.hasNext()) {
                processMetric(connection, metric);
            }
        } catch (SQLException sqlException) {
            LOG.debug("Successfully loaded default metrics from:" + DEFAULT_METRICS);
        } finally {
            Gauge gauge = gaugeMap.get(ORACLEDB_METRIC_PREFIX + "up");
            if (gauge == null) {
                Gauge upgauge = Gauge.build().name(ORACLEDB_METRIC_PREFIX + "up").help("Whether the Oracle database server is up.").register();
                upgauge.set(isConnectionSuccessful);
                gaugeMap.put(ORACLEDB_METRIC_PREFIX + "up", upgauge);
            } else gauge.set(isConnectionSuccessful);
        }
    }

    /**
     * Process Metric config, issue SQL query, and translate into Prometheus format for publish at /metric
     * Context          string
     * Labels           []string
     * MetricsDesc      map[string]string
     * MetricsType      map[string]string
     * MetricsBuckets   map[string]map[string]string
     * FieldToAppend    string
     * Request          string
     * IgnoreZeroResult bool
     */
    private void processMetric(Connection connection, Iterator<JsonNode> metric) throws SQLException {
        JsonNode next = metric.next();
        //todo ignore case
        String context = next.get("context").asText(); // eg context = "teq"
        String metricsType = next.get("metricstype") == null ? "" :next.get("metricstype").asText(); // gauge todo counter, histogram, or unspecified
        JsonNode metricsdescNode = next.get("metricsdesc");
        // eg metricsdesc = { enqueued_msgs = "Total enqueued messages.", dequeued_msgs = "Total dequeued messages.", remained_msgs = "Total remained messages."}
        Iterator<Map.Entry<String, JsonNode>> metricsdescIterator = metricsdescNode.fields();
        Map<String, String> metricsDescMap = new HashMap<>();
        while(metricsdescIterator.hasNext()) {
            Map.Entry<String, JsonNode> metricsdesc = metricsdescIterator.next();
            metricsDescMap.put(metricsdesc.getKey(), metricsdesc.getValue().asText());
        }
        LOG.debug("----context:" + context);
        String[] labelNames = new String[0];
        if (next.get("labels") != null) {
            int size = next.get("labels").size();
            Iterator<JsonNode> labelIterator = next.get("labels").iterator();
            labelNames = new String[size];
            for (int i = 0; i < size; i++) {
                labelNames[i] = labelIterator.next().asText();
            }
            LOG.debug("\n");
        }
        String request = next.get("request").asText(); // the sql query
        String ignorezeroresult = next.get("ignorezeroresult") == null ? "false" : next.get("ignorezeroresult").asText(); //todo
        ResultSet resultSet;
        try {
             resultSet = connection.prepareStatement(request).executeQuery();
        } catch(SQLException e) { //this can be due to table not existing etc.
            LOG.debug("OracleDBMetricsExporter.processMetric  during:" + request);
            LOG.debug("OracleDBMetricsExporter.processMetric  exception:" + e);
            return;
        }
        while (resultSet.next()) {
            translateQueryToPrometheusMetric(context,  metricsDescMap, labelNames, resultSet);
        }
    }

    private void translateQueryToPrometheusMetric(String context, Map<String, String> metricsDescMap,
                                                  String[] labelNames,
                                                  ResultSet resultSet) throws SQLException {
        String[] labelValues = new String[labelNames.length];
        Map<String, Integer> sqlQueryResults =
                extractGaugesAndLabelValues(context, metricsDescMap, labelNames, resultSet, labelValues, resultSet.getMetaData().getColumnCount());
        setLabelValues(context, labelNames, labelValues, sqlQueryResults.entrySet().iterator());
    }

    /**
     * Creates Gauges and gets label values
     * @param context
     * @param metricsDescMap
     * @param labelNames
     * @param resultSet
     * @param labelValues
     * @param columnCount
     * @throws SQLException
     */
    private Map<String, Integer> extractGaugesAndLabelValues(
            String context, Map<String, String> metricsDescMap, String[] labelNames, ResultSet resultSet,
            String[] labelValues, int columnCount) throws SQLException {
        Map<String, Integer> sqlQueryResults = new HashMap<>();
        String columnName;
        String columnTypeName;
        for (int i = 0; i < columnCount; i++) { //for each column...
            columnName = resultSet.getMetaData().getColumnName(i + 1).toLowerCase();
            columnTypeName = resultSet.getMetaData().getColumnTypeName(i + 1);
            if (columnTypeName.equals("VARCHAR2"))  //.  typename is 2/NUMBER or 12/VARCHAR2
                ;
            else
                sqlQueryResults.put(resultSet.getMetaData().getColumnName(i + 1), resultSet.getInt(i + 1));
            String gaugeName = ORACLEDB_METRIC_PREFIX + context + "_" + columnName;
            LOG.debug("---gaugeName:" + gaugeName);
            Gauge gauge = gaugeMap.get(gaugeName);
            if (gauge == null) {
                if(metricsDescMap.containsKey(columnName)) {
                    if (labelNames.length > 0) {
                        gauge = Gauge.build().name(gaugeName.toLowerCase()).help(metricsDescMap.get(columnName)).labelNames(labelNames).register();
                    } else gauge = Gauge.build().name(gaugeName.toLowerCase()).help(metricsDescMap.get(columnName)).register();
                    gaugeMap.put(gaugeName, gauge);
                }
            }
            for (int ii = 0; ii< labelNames.length; ii++) {
                if(labelNames[ii].equals(columnName)) labelValues[ii] = resultSet.getString(i+1);
            }
        }
        return sqlQueryResults;
    }

    private void setLabelValues(String context, String[] labelNames, String[] labelValues, Iterator<Map.Entry<String, Integer>> sqlQueryRestulsEntryIterator) {
        while(sqlQueryRestulsEntryIterator.hasNext()) { //for each column
            Map.Entry<String, Integer> sqlQueryResultsEntry =   sqlQueryRestulsEntryIterator.next();
            boolean isLabel = false;
            for (int ii = 0; ii< labelNames.length; ii++) {
                if(labelNames[ii].equals(sqlQueryResultsEntry.getKey())) isLabel =true;  // continue
            }
            if(!isLabel) {
                int valueToSet = (int) Math.rint(sqlQueryResultsEntry.getValue().intValue());
                if(labelValues.length >0 )
                    try {
                        gaugeMap.get(ORACLEDB_METRIC_PREFIX + context + "_" + sqlQueryResultsEntry.getKey().toLowerCase()).labels(labelValues).set(valueToSet);
                    } catch (Exception ex) {
                        //todo gate the get above as is done with if(metricsDescMap.containsKey(columnName)) previously to avoid NPE
                        LOG.error("OracleDBMetricsExporter.translateQueryToPrometheusMetric Exc:" + ex);
                        //     ex.printStackTrace();
                    }
                else gaugeMap.get(ORACLEDB_METRIC_PREFIX + context + "_" + sqlQueryResultsEntry.getKey().toLowerCase()).set(valueToSet);
            }
        }
    }
}
