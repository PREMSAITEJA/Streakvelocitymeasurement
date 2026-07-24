cmake_minimum_required(VERSION 3.22.1)
project(HighFPSRecorder)

# NOTE: NDK r28+ automatically applies 16 KB alignment to all native libraries
# (including pre-built ones). No manual linker flags needed.
# This file is kept for future native code additions.

# Add your native sources here when needed in the future
# Example:
# add_library(mylib SHARED src/native.cpp)
# (16 KB alignment will be automatically applied by NDK r28+)
