package com.vortexnode.chargingpower;

interface IPowerSupplyReader {
    String readBatteryMetrics();
    void destroy() = 16777114;
}
