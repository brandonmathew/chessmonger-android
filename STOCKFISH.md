# Stockfish in ChessMonger Android

Stockfish 17.1 (GPLv3, https://stockfishchess.org) runs as a separate
process, driven over UCI by `StockfishEngine.java`. It is not linked
into the app; it ships as `libstockfish.so` (a PIE executable) inside
the APK and is executed from the app's native library directory (the
manifest sets `extractNativeLibs="true"`).

Binary: `stockfish-bin/arm64-v8a/libstockfish.so`, the same build
proven in Gambit Chess v1.22.0 — official `sf_17.1` source with its
two default NNUE networks, compiled for arm64-v8a with the NDK
(`COMP=ndk`, `-static-libstdc++`, PIE, Bionic-only deps).

Full GPL source for the exact version:
https://github.com/official-stockfish/Stockfish/tree/sf_17.1
(see `Copying.txt` there). Personal use is fine; publishing this app
would need a source offer for Stockfish.
