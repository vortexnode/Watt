package com.vortexnode.chargingpower;

interface IPowerSupplyReader {
    String readBatteryMetrics() = 1;
    void destroy() = 16777114;
}
