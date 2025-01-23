# C++ Header Dependencies Normalization on Case-Insensitive File System

AKA Windows.

Things like `#include <Windows.h>` vs `#include <windows.h>` are considered different from Gradle but are valid sloppy coding on system like Windows.
To work around that issue, we normalize the detected headers.
