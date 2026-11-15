    .text
    .globl  _sumx
_sumx:
    movl    %edi, %eax
    addl    %esi, %eax
    ret
