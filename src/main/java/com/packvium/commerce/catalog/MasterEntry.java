package com.packvium.commerce.catalog;

/**
 * The master-data kinds a facility override may replace -- PHP's
 * {@code ItemMaster|CartonMaster|PalletMaster} union, closed the same way.
 */
public sealed interface MasterEntry permits ItemMaster, CartonMaster, PalletMaster {
    String id();
}
