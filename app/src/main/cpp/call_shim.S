// call_shim.S —— 精确控制寄存器的调用垫片
// 目标函数（so 内 chain，偏移由 SignComputer.Variant 给出：练习 +0x4078 / PK +0x40A8）签名：
//   std::string chain(sret=x8, x1=const std::string&, x2=const std::string&, w3=int)
// 本垫片与 so 版本无关 —— 偏移由调用方算好后再传 fn。
// libc++ std::string 布局：短串 [0]=(len<<1)，长串 [0]=cap|1 / [8]=len / [16]=ptr
//
// call_shim(fn=x0, out=x1, a=x2, b=x3, c=w4)
.global call_shim
.type call_shim, %function
call_shim:
    stp x29, x30, [sp, #-16]!
    mov x9, x0          // fn
    mov x8, x1          // sret out
    mov x1, x2          // a
    mov x2, x3          // b
    mov w3, w4          // c
    mov x0, #0
    blr x9
    ldp x29, x30, [sp], #16
    ret
