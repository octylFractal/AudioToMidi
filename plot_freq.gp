set terminal x11 persist
set title "Spectrogram"
unset key
plot "freq_fmo.csv" matrix using ($1/20):($2/20):3 with image
