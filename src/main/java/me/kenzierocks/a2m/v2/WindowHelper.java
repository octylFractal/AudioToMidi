package me.kenzierocks.a2m.v2;

class WindowHelper {
    
    private static final ThreadLocal<double[]> WINDOWING_CACHE = new ThreadLocal<>();
    
    public static double[] getWindowingArray(int len) {
        double[] array = WINDOWING_CACHE.get();
        if (array == null || array.length < len) {
            array = new double[len];
            WINDOWING_CACHE.set(array);
        }
        return array;
    }

}
