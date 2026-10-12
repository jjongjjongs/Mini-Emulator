    .arm
    .section .text
    .global _start
_start:
    b       start
    .fill   0xB2 - 4, 1, 0      @ header (logo/title left blank)
    .byte   0x96                @ fixed value checked by emulators
    .fill   0xC0 - 0xB3, 1, 0
start:
    @ mode 3, BG2 on
    mov     r0, #0x04000000
    ldr     r1, =0x0403
    strh    r1, [r0]
    @ fill the screen with red (BGR555 0x001F)
    mov     r2, #0x06000000
    ldr     r3, =0x001F001F
    ldr     r4, =(240*160/2)
1:  str     r3, [r2], #4
    subs    r4, r4, #1
    bne     1b
    @ SRAM[0] = 0x42 marks that the game ran
    mov     r5, #0x0E000000
    mov     r1, #0x42
    strb    r1, [r5]
loop:
    @ SRAM[1] = low byte of KEYINPUT (active low) so input can be checked
    ldr     r0, =0x04000130
    ldrh    r1, [r0]
    strb    r1, [r5, #1]
    b       loop
    .ltorg
    .ascii  "SRAM_V113"
    .balign 4
