package co.hotwax.reconciler

import groovy.json.JsonSlurper
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Bounded, fail-closed HTTP client for reference and target Moqui instances.
 */
class RemoteInstanceClient {
    private static final Logger logger = LoggerFactory.getLogger(RemoteInstanceClient.class)
    private static final int MAX_RESPONSE_BYTES = 25 * 1024 * 1024
    private static final int MAX_ERROR_BYTES = 32 * 1024

    static class FetchResult {
        boolean success
        List<Map<String, Object>> records = []
        Integer statusCode
        String endpoint
        String error
    }

    static FetchResult fetchRemoteEntityResult(String baseUrl, String apiKey, String entityName) {
        FetchResult result = new FetchResult()
        HttpURLConnection conn = null
        try {
            URL base = validateRemoteBaseUrl(baseUrl)
            boolean enumeration = entityName == "moqui.basic.Enumeration"
            String endpoint = enumeration ?
                    "${base.toExternalForm()}/rest/s1/admin/enums?pageNoLimit=true" :
                    "${base.toExternalForm()}/apps/tools/Entity/DataExport/EntityExport"
            result.endpoint = endpoint

            conn = openConnection(new URL(endpoint), enumeration ? "GET" : "POST", apiKey)
            if (!enumeration) {
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                byte[] payload = ("entityNames=" + URLEncoder.encode(entityName, StandardCharsets.UTF_8.name()) +
                        "&fileType=CSV&output=browser&dependentLevels=0&useTableColumnNames=No&filterMap=%5B%3A%5D")
                        .getBytes(StandardCharsets.UTF_8)
                conn.setFixedLengthStreamingMode(payload.length)
                conn.outputStream.withCloseable { it.write(payload) }
            }

            int status = conn.responseCode
            result.statusCode = status
            if (status < 200 || status >= 300) {
                result.error = "HTTP ${status} fetching ${entityName}: " + readResponse(conn.errorStream, MAX_ERROR_BYTES)
                return result
            }

            String body = readResponse(conn.inputStream, MAX_RESPONSE_BYTES)
            if (!body?.trim()) {
                result.error = "Empty response fetching ${entityName}"
                return result
            }
            if (looksLikeHtml(body)) {
                result.error = "Unexpected HTML response fetching ${entityName}; authentication or endpoint configuration may be invalid"
                return result
            }

            result.records = enumeration ? parseJsonRecords(body) : parseMoquiCsv(body)
            result.success = true
            return result
        } catch (Exception e) {
            result.error = "${e.class.simpleName}: ${e.message}"
            logger.warn("Remote entity fetch failed for ${entityName}: ${result.error}")
            return result
        } finally {
            conn?.disconnect()
        }
    }

    /** Compatibility wrapper. New callers should inspect FetchResult.success. */
    static List<Map<String, Object>> fetchRemoteEntity(String baseUrl, String apiKey, String entityName) {
        FetchResult result = fetchRemoteEntityResult(baseUrl, apiKey, entityName)
        return result.success ? result.records : []
    }

    static List<Map<String, Object>> parseMoquiCsv(String body) {
        int firstLineEnd = body.indexOf('\n')
        if (firstLineEnd < 0) throw new IllegalArgumentException("Moqui CSV response has no header row")
        String csvText = body.substring(firstLineEnd + 1)
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .get()
        CSVParser parser = format.parse(new StringReader(csvText))
        try {
            List<Map<String, Object>> records = []
            for (CSVRecord record : parser) {
                Map<String, Object> row = new LinkedHashMap<>()
                record.toMap().each { String key, String value -> row.put(key?.trim(), value) }
                if (!row.isEmpty()) records.add(row)
            }
            return records
        } finally {
            parser.close()
        }
    }

    static Map<String, String> authenticationHeaders(String apiKey) {
        Map<String, String> headers = [:]
        String value = apiKey?.trim()
        if (!value) return headers

        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            headers.Authorization = "Bearer " + value.substring(7).trim()
        } else if (value.regionMatches(true, 0, "Cookie ", 0, 7)) {
            headers.Cookie = value.substring(7).trim()
        } else if (value.contains("=") || value.contains(";")) {
            headers.Cookie = value
        } else {
            headers.Authorization = "Bearer " + value
        }
        return headers
    }

    private static HttpURLConnection openConnection(URL url, String method, String apiKey) {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection()
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.doOutput = method == "POST"
        conn.setRequestProperty("Accept", "application/json, text/csv, text/plain")
        conn.setRequestProperty("User-Agent", "Moqui-Data-Reconciler/2.0")
        authenticationHeaders(apiKey).each { String name, String value -> conn.setRequestProperty(name, value) }
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        return conn
    }

    static URL validateRemoteBaseUrl(String baseUrl) {
        if (!baseUrl?.trim()) throw new IllegalArgumentException("Remote base URL is required")
        String normalized = baseUrl.trim().replaceAll('/+$', '')
        URI uri = new URI(normalized)
        if (!"https".equalsIgnoreCase(uri.scheme)) {
            throw new IllegalArgumentException("Only HTTPS remote instance URLs are allowed")
        }
        if (!uri.host || uri.userInfo || uri.query || uri.fragment) {
            throw new IllegalArgumentException("Remote base URL must contain only an HTTPS scheme, host, optional port, and base path")
        }
        if (!isAllowedHost(uri.host)) {
            throw new SecurityException("Remote host [${uri.host}] is not in data.reconciler.allowedHosts")
        }

        for (InetAddress address : InetAddress.getAllByName(uri.host)) {
            if (isPrivateAddress(address)) {
                throw new SecurityException("Remote host [${uri.host}] resolves to a private or local address")
            }
        }
        return uri.toURL()
    }

    private static boolean isAllowedHost(String host) {
        String configured = System.getProperty("data.reconciler.allowedHosts")?.trim()
        if (!configured) return true
        String normalized = host.toLowerCase(Locale.ROOT)
        return configured.split(",").collect { it.trim().toLowerCase(Locale.ROOT) }.find { String allowed ->
            allowed && (normalized == allowed || (allowed.startsWith("*.") && normalized.endsWith(allowed.substring(1))))
        } != null
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.anyLocalAddress || address.loopbackAddress || address.linkLocalAddress ||
                address.siteLocalAddress || address.multicastAddress) return true
        byte[] raw = address.address
        if (raw.length == 16 && ((raw[0] & 0xFE) == 0xFC)) return true
        return false
    }

    private static List<Map<String, Object>> parseJsonRecords(String body) {
        Object json = new JsonSlurper().parseText(body)
        if (json instanceof List) return (List<Map<String, Object>>) json
        if (json instanceof Map && json.itemList instanceof List) {
            return (List<Map<String, Object>>) json.itemList
        }
        throw new IllegalArgumentException("JSON response does not contain a record list")
    }

    private static String readResponse(InputStream stream, int maxBytes) {
        if (stream == null) return ""
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        int total = 0
        stream.withCloseable {
            int read
            while ((read = it.read(buffer)) >= 0) {
                total += read
                if (total > maxBytes) throw new IOException("Response exceeds ${maxBytes} bytes")
                output.write(buffer, 0, read)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private static boolean looksLikeHtml(String body) {
        String trimmed = body?.trim()?.toLowerCase(Locale.ROOT)
        return trimmed?.startsWith("<!doctype html") || trimmed?.startsWith("<html")
    }

    private static boolean containsImportError(String body) {
        String lower = body?.toLowerCase(Locale.ROOT) ?: ""
        return lower.contains('"error"') || lower.contains("errormessage") ||
                lower.contains("validation error") || lower.contains("exception")
    }

    private static String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) return value
        return value.substring(0, max) + "..."
    }
}
