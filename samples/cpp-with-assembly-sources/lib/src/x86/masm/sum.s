.386
.model    flat

INCLUDE <ks386.inc>
PUBLIC    _sumx
_TEXT     SEGMENT
_sumx    PROC
mov    eax, DWORD PTR 4[esp]
add    eax, DWORD PTR 8[esp]
ret    0
_sumx    ENDP
_TEXT   ENDS
END
