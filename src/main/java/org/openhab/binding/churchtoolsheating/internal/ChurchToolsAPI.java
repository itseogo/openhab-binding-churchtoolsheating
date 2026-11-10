package org.openhab.binding.churchtoolsheating.internal;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ChurchToolsAPI {

    private final String baseUrl;
    private final String token;

    public ChurchToolsAPI(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    public List<Resource> getResources(Integer filterTypeId) throws Exception {
        URL url = new URL(baseUrl + "/api/resources");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Login " + token);
        conn.setRequestProperty("Accept", "application/json");

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("Failed : HTTP error code : " + conn.getResponseCode());
        }

        JsonObject jsonResponse;
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
            jsonResponse = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray data = jsonResponse.getAsJsonArray("data");
        
        List<Resource> resources = new ArrayList<>();
        for (JsonElement el : data) {
            JsonObject obj = el.getAsJsonObject();
            Resource res = new Resource();
            res.id = obj.get("id").getAsInt();
            res.name = obj.get("name").getAsString();
            if (obj.has("resourceTypeId") && !obj.get("resourceTypeId").isJsonNull()) {
                res.resourceTypeId = obj.get("resourceTypeId").getAsInt();
            }
            if (filterTypeId == null || filterTypeId.equals(res.resourceTypeId)) {
                resources.add(res);
            }
        }
        return resources;
    }

    public List<Booking> getBookings(int resourceId, boolean onlyConfirmed) throws Exception {
        // Look back 1 day to catch overnight/early morning bookings and events currently in progress
        java.time.LocalDate startDate = java.time.LocalDate.now().minusDays(1);
        java.time.LocalDate nextYear = startDate.plusYears(1);
        String dateStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String toDateStr = nextYear.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        // Pass both from_date / to_date and from / to for cross-version compatibility
        String urlString = baseUrl + "/api/bookings?resource_ids[]=" + resourceId 
                + "&from_date=" + dateStr + "&to_date=" + toDateStr 
                + "&from=" + dateStr + "&to=" + toDateStr;
        if (onlyConfirmed) {
            urlString += "&status_ids[]=2";
        }
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Login " + token);
        conn.setRequestProperty("Accept", "application/json");

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("Failed to fetch bookings: HTTP error code : " + conn.getResponseCode());
        }

        JsonObject jsonResponse;
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream())) {
            jsonResponse = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray data = jsonResponse.getAsJsonArray("data");
        
        List<Booking> bookings = new ArrayList<>();
        for (JsonElement el : data) {
            JsonObject bookingObj = el.getAsJsonObject();
            
            JsonObject base = bookingObj.getAsJsonObject("base");
            JsonObject calculated = bookingObj.has("calculated") && !bookingObj.get("calculated").isJsonNull() 
                    ? bookingObj.getAsJsonObject("calculated") : base;
            
            Booking b = new Booking();
            b.id = base != null && base.has("id") ? base.get("id").getAsInt() : bookingObj.get("id").getAsInt();
            
            if (base != null && base.has("title") && !base.get("title").isJsonNull()) {
                b.caption = base.get("title").getAsString();
            } else if (bookingObj.has("caption") && !bookingObj.get("caption").isJsonNull()) {
                b.caption = bookingObj.get("caption").getAsString();
            } else {
                b.caption = "Termin";
            }
            
            String startStr = calculated != null && calculated.has("startDate") && !calculated.get("startDate").isJsonNull() 
                    ? calculated.get("startDate").getAsString() : null;
            String endStr = calculated != null && calculated.has("endDate") && !calculated.get("endDate").isJsonNull() 
                    ? calculated.get("endDate").getAsString() : null;
            
            b.startDate = parseDate(startStr);
            b.endDate = parseDate(endStr);
            
            if (b.startDate != null && b.endDate != null) {
                bookings.add(b);
            }
        }
        return bookings;
    }
    
    ZonedDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            dateStr = dateStr.trim();
            if (dateStr.length() == 10) {
                // all day event like "2025-12-27"
                java.time.LocalDate ld = java.time.LocalDate.parse(dateStr);
                return ld.atStartOfDay(ZoneId.systemDefault());
            } else if (dateStr.endsWith("Z") || dateStr.contains("+") || (dateStr.contains("-") && dateStr.lastIndexOf('-') > 10)) {
                return ZonedDateTime.parse(dateStr);
            } else {
                // local datetime string like "2026-11-01 10:00:00" or "2026-11-01T10:00:00"
                String normalized = dateStr.replace(' ', 'T');
                LocalDateTime ldt = LocalDateTime.parse(normalized);
                return ldt.atZone(ZoneId.systemDefault());
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static class Resource {
        public int id;
        public String name;
        public int resourceTypeId;
    }

    public static class Booking {
        public int id;
        public ZonedDateTime startDate;
        public ZonedDateTime endDate;
        public String caption;
    }
}
