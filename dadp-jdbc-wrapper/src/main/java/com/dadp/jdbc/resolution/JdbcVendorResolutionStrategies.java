package com.dadp.jdbc.resolution;

public final class JdbcVendorResolutionStrategies {

    private JdbcVendorResolutionStrategies() {
    }

    public static JdbcVendorResolutionStrategy forVendor(String dbVendor) {
        String vendor = dbVendor != null ? dbVendor.toLowerCase() : "";
        if (vendor.contains("sqream")) {
            return new SqreamJdbcVendorResolutionStrategy(dbVendor);
        }
        return new StandardJdbcVendorResolutionStrategy(dbVendor);
    }
}
