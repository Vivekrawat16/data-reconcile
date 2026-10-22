package co.hotwax.reconciler

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URL
import java.net.Proxy
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * RemoteInstanceClient
 * Connects to a reference Moqui/HotWax instance via HTTP REST APIs,
 * retrieves entity datasets, and returns standardized record maps.
 */
class RemoteInstanceClient {
    private static final Logger logger = LoggerFactory.getLogger(RemoteInstanceClient.class)

    /**
     * Fetches entity records from a remote Moqui instance.
     * @param baseUrl Base URL of the reference instance (e.g. https://rails-uat.hotwax.io or https://krewe-maarg-uat.hotwax.io)
     * @param apiKey Optional Bearer token or Cookie string
     * @param entityName Full Moqui entity name
     * @return List of record maps
     */
    static List<Map<String, Object>> fetchRemoteEntity(String baseUrl, String apiKey, String entityName) {
        if (!baseUrl) {
            logger.warn("RemoteInstanceClient: Reference URL is empty, skipping remote fetch for ${entityName}")
            return []
        }

        String cleanUrl = baseUrl.trim().replaceAll("/+\$", "")
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://" + cleanUrl
        }

        List<Map<String, Object>> records = []

        try {
            if (entityName == "moqui.basic.Enumeration") {
                // High-speed endpoint for Enums
                String endpoint = "${cleanUrl}/rest/s1/admin/enums?pageNoLimit=true"
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection(Proxy.NO_PROXY)
                conn.setRequestMethod("GET")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Moqui-Data-Reconciler")
                if (apiKey) {
                    if (apiKey.startsWith("Bearer ") || apiKey.startsWith("bearer ")) {
                        conn.setRequestProperty("Authorization", apiKey)
                    } else if (apiKey.contains("=") || apiKey.contains(";")) {
                        conn.setRequestProperty("Cookie", apiKey)
                    } else {
                        conn.setRequestProperty("Authorization", "Bearer " + apiKey)
                    }
                }
                conn.setConnectTimeout(15000)
                conn.setReadTimeout(60000)

                int responseCode = conn.getResponseCode()
                if (responseCode >= 200 && responseCode < 300) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
                    StringBuilder sb = new StringBuilder()
                    String line
                    while ((line = reader.readLine()) != null) sb.append(line)
                    reader.close()

                    def json = new JsonSlurper().parseText(sb.toString())
                    if (json instanceof List) {
                        records = (List<Map<String, Object>>) json
                    } else if (json instanceof Map && json.containsKey("itemList")) {
                        records = (List<Map<String, Object>>) json.get("itemList")
                    }
                } else {
                    logger.warn("RemoteInstanceClient: HTTP ${responseCode} fetching ${entityName} from ${endpoint}")
                }
            } else {
                // Fallback to DataExport endpoint for other Moqui entities
                String endpoint = "${cleanUrl}/apps/tools/Entity/DataExport/EntityExport"
                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection(Proxy.NO_PROXY)
                conn.setRequestMethod("POST")
                conn.setDoOutput(true)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("User-Agent", "Moqui-Data-Reconciler")
                if (apiKey) {
                    if (apiKey.contains("=") || apiKey.contains(";")) {
                        conn.setRequestProperty("Cookie", apiKey)
                    } else {
                        conn.setRequestProperty("Authorization", "Bearer " + apiKey)
                    }
                }
                conn.setConnectTimeout(15000)
                conn.setReadTimeout(60000)

                String postData = "entityNames=${URLEncoder.encode(entityName, 'UTF-8')}&fileType=CSV&output=browser&dependentLevels=0&useTableColumnNames=No&filterMap=%5B%3A%5D"
                conn.getOutputStream().write(postData.getBytes("UTF-8"))

                int responseCode = conn.getResponseCode()
                if (responseCode >= 200 && responseCode < 300) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))
                    List<String> lines = []
                    String line
                    while ((line = reader.readLine()) != null) lines.add(line)
                    reader.close()

                    if (lines.size() >= 2) {
                        // Header is at line 2 (line 1 is Moqui metadata)
                        String[] headers = lines.get(1).split(",")
                        for (int i = 2; i < lines.size(); i++) {
                            String[] values = lines.get(i).split(",")
                            Map<String, Object> row = [:]
                            for (int c = 0; c < headers.length && c < values.length; c++) {
                                String col = headers[c].trim().replaceAll("^\"|\"\$", "")
                                String val = values[c].trim().replaceAll("^\"|\"\$", "")
                                row.put(col, val)
                            }
                            if (!row.isEmpty()) records.add(row)
                        }
                    }
                } else {
                    logger.warn("RemoteInstanceClient: HTTP ${responseCode} fetching ${entityName} from ${endpoint}")
                }
            }
        } catch (java.net.UnknownHostException uhe) {
            logger.error("RemoteInstanceClient: Unable to resolve host '${uhe.message}'. Please check that Reference URL is a valid domain (e.g. https://rails-uat.hotwax.io or https://krewe-maarg-uat.hotwax.io).")
        } catch (Exception e) {
            logger.error("Error fetching remote data for ${entityName} from ${baseUrl}: ${e.message}", e)
        }

        return records
    }

    /**
     * Uploads/imports an entity XML payload to a remote Moqui instance.
     */
    static int uploadRemoteXml(String baseUrl, String apiKey, String xmlPayload) {
        if (!baseUrl || !xmlPayload?.trim()) return 0

        String cleanUrl = baseUrl.trim().replaceAll("/+\$", "")
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://" + cleanUrl
        }

        try {
            String endpoint = "${cleanUrl}/apps/tools/Entity/DataImport/load"
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection(Proxy.NO_PROXY)
            conn.setRequestMethod("POST")
            conn.setDoOutput(true)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "Moqui-Data-Reconciler")
            if (apiKey) {
                if (apiKey.contains("=") || apiKey.contains(";")) {
                    conn.setRequestProperty("Cookie", apiKey)
                } else {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey)
                }
            }
            conn.setConnectTimeout(30000)
            conn.setReadTimeout(120000)

            String postData = "xmlText=" + URLEncoder.encode("<entity-facade-xml>\n" + xmlPayload + "\n</entity-facade-xml>", "UTF-8")
            conn.getOutputStream().write(postData.getBytes("UTF-8"))

            int responseCode = conn.getResponseCode()
            if (responseCode >= 200 && responseCode < 400) {
                logger.info("RemoteInstanceClient: Successfully uploaded XML payload to ${endpoint}")
                return 1
            } else {
                logger.warn("RemoteInstanceClient: Failed uploading XML to ${endpoint}, HTTP ${responseCode}")
            }
        } catch (Exception e) {
            logger.error("RemoteInstanceClient: Error uploading XML to ${baseUrl}: ${e.message}", e)
        }
        return 0
    }
}
