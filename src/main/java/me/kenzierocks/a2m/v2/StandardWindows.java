package me.kenzierocks.a2m.v2;

public enum StandardWindows implements Window {
    HANNING {

        @Override
        public double apply(int i, int nn) {
            return (0.5 * (1.0 - Math.cos(2.0 * Math.PI * (double) i / (double) (nn - 1))));
        }
    },
    HAMMING {

        @Override
        public double apply(int i, int nn) {
            return (0.54 - 0.46 * Math.cos(2.0 * Math.PI * (double) i / (double) (nn - 1)));
        }

    };

}
