    .text
    .globl  _sumx
_sumx:
    movl    8(%esp), %eax
    addl    4(%esp), %eax
    ret
