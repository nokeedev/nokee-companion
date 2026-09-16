@echo off
rem
rem Regenerates the import-library fixtures the ABI extractor tests read, with the Microsoft tools.
rem
rem Usage: from a Visual Studio Developer Command Prompt, build-fixtures.bat
rem
rem lib.exe reads exports.def and writes the import library straight from it, so nothing has to be
rem compiled or linked first: a short import object carries the exported name, the DLL that defines
rem it, and the ordinal or hint, all of which the .def already spells.
rem
rem The ELF and Mach-O fixtures are built elsewhere; see build-fixtures.sh. That script builds these
rem same import libraries with llvm-dlltool when Windows is not at hand. The two agree on every field
rem the reader reports except the timestamp and the hint of a named export, neither of which is
rem asserted on.

setlocal
set here=%~dp0

if not exist "%here%import-library\x86"    mkdir "%here%import-library\x86"
if not exist "%here%import-library\x86_64" mkdir "%here%import-library\x86_64"

echo ==^> import-library (x86)
lib.exe /nologo /machine:X86 /def:"%here%import-library\exports.def" /out:"%here%import-library\x86\exports.lib"
if errorlevel 1 goto :failed

echo ==^> import-library (x86_64)
lib.exe /nologo /machine:X64 /def:"%here%import-library\exports.def" /out:"%here%import-library\x86_64\exports.lib"
if errorlevel 1 goto :failed

rem lib.exe writes an export file next to each import library; only the import library is a fixture.
del /q "%here%import-library\x86\exports.exp" 2>nul
del /q "%here%import-library\x86_64\exports.exp" 2>nul

echo ==^> done
exit /b 0

:failed
echo failed 1>&2
exit /b 1
