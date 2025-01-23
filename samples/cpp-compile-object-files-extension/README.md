
All about the objectFiles extension on all native compile tasks

Explain that it returns the object files only produced by the task. Everything is wired to follow that convention.

Mention that CppBinary#objects is shadowed.

Mention that all CppCompile (and native tasks) tasks are decorated with objectFiles extensions and should use that instead of filtering the objectFileDir property.
