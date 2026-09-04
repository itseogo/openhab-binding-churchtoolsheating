package org.openhab.binding.churchtoolsheating.internal;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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

        JsonObject jsonResponse = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
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
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate nextYear = today.plusYears(1);
        String dateStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String toDateStr = nextYear.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        String urlString = baseUrl + "/api/bookings?resource_ids[]=" + resourceId + "&from_date=" + dateStr + "&to_date=" + toDateStr;
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

        JsonObject jsonResponse = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
        JsonArray data = jsonResponse.getAsJsonArray("data");
        
        List<Booking> bookings = new ArrayList<>();
        for (JsonElement el : data) {
            JsonObject bookingObj = el.getAsJsonObject();
            
            JsonObject base = bookingObj.getAsJsonObject("base");
            JsonObject calculated = bookingObj.getAsJsonObject("calculated");
            
            Booking b = new Booking();
            b.id = base.get("id").getAsInt();
            b.caption = base.has("title") && !base.get("title").isJsonNull() ? base.get("title").getAsString() : "Termin";
            
            String startStr = calculated.get("startDate").getAsString();
            String endStr = calculated.get("endDate").getAsString();
            
            b.startDate = parseDate(startStr);
            b.endDate = parseDate(endStr);
            
            if (b.startDate != null && b.endDate != null) {
                bookings.add(b);
            }
        }
        return bookings;
    }
    
    private ZonedDateTime parseDate(String dateStr) {
        try {
            if (dateStr.length() == 10) {
                // all day event like "2025-12-27"
                java.time.LocalDate ld = java.time.LocalDate.parse(dateStr);
                return ld.atStartOfDay(java.time.ZoneId.systemDefault());
            } else {
                return ZonedDateTime.parse(dateStr);
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
