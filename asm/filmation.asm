;-----------------------------------------------------------------------------
; FILMATION BRICK — ZX Spectrum 48K (Z80)
;
; Pinta ladrillos 3D sólidos con proyección isométrica 2:1 al estilo
; Filmation (Knight Lore, Alien 8): tres caras visibles, rombo superior
; y laterales en paralelogramo con aristas verticales.
;
; Controles:
;   O/P o cursor izq/der   eje X
;   Q/A o cursor arr/abj   eje Y
;   W/S                    subir / bajar Z
;   SPACE o fuego          colocar ladrillo sólido
;   M                      borrar ladrillo
;   0                      vaciar el mundo
;
; Ensamblar:  python3 tools/z80asm.py asm/filmation.asm
; Ejecutar:   RANDOMIZE USR 32768   (o cargar el .sna)
;-----------------------------------------------------------------------------

        ORG     $8000

GRID_W          EQU     6
GRID_H          EQU     6
GRID_D          EQU     4
ORIGIN_X        EQU     128
ORIGIN_Y        EQU     28
ATTR_BRICK      EQU     $46         ; tinta amarilla brillante, papel negro

; Patrón de relleno de cada cara (ver maybe_plot)
PAT_TOP         EQU     0           ; cara superior, denso
PAT_LEFT        EQU     1           ; cara izquierda, rayas horizontales
PAT_RIGHT       EQU     2           ; cara derecha, rayas verticales
PAT_EDGE        EQU     3           ; arista sólida
PAT_GHOST       EQU     4           ; cursor, punteado
PAT_FLOOR       EQU     5           ; suelo, muy ralo

;-----------------------------------------------------------------------------
; Arranque
;-----------------------------------------------------------------------------
start:
        di
        im      1
        ld      sp,$FF00
        xor     a
        out     ($FE),a             ; borde negro
        call    init_world
        call    draw_world
        ld      a,1
        ld      (done_flag),a
main_loop:
        call    read_key
        or      a
        jr      z,main_loop
        call    handle_key
        call    draw_world
wait_up:
        call    read_key
        or      a
        jr      nz,wait_up
        jr      main_loop

;-----------------------------------------------------------------------------
; Mundo de demostración: escalera + muro + un ladrillo suelto
;-----------------------------------------------------------------------------
init_world:
        ld      hl,world
        ld      de,world+1
        ld      (hl),0
        ld      bc,GRID_W*GRID_H*GRID_D-1
        ldir
        ld      b,1                 ; x
        ld      c,1                 ; y
        ld      e,0                 ; z
        call    put_brick
        ld      b,1
        ld      c,3
        ld      e,0
        call    put_brick
        ld      b,2
        ld      c,3
        ld      e,0
        call    put_brick
        ld      b,2
        ld      c,3
        ld      e,1
        call    put_brick
        ld      b,3
        ld      c,3
        ld      e,0
        call    put_brick
        ld      b,3
        ld      c,3
        ld      e,1
        call    put_brick
        ld      b,3
        ld      c,3
        ld      e,2
        call    put_brick
        ld      b,4
        ld      c,1
        ld      e,0
        call    put_brick
        ld      b,4
        ld      c,2
        ld      e,0
        call    put_brick
        ld      b,4
        ld      c,3
        ld      e,0
        call    put_brick
        ld      a,2
        ld      (cur_x),a
        ld      a,2
        ld      (cur_y),a
        xor     a
        ld      (cur_z),a
        ret

put_brick:
        call    world_addr
        ld      (hl),1
        ret

; HL = world + x + y*6 + z*36   B=x C=y E=z
world_addr:
        ld      a,c
        add     a,a                 ; y*2
        ld      l,a
        add     a,a                 ; y*4
        add     a,l                 ; y*6
        add     a,b                 ; +x
        ld      l,a
        ld      a,e
        add     a,a
        add     a,a                 ; z*4
        ld      h,a
        add     a,a                 ; z*8
        add     a,a                 ; z*16
        add     a,a                 ; z*32
        add     a,h                 ; z*36
        add     a,l
        ld      l,a
        ld      h,0
        ld      de,world
        add     hl,de
        ret

;-----------------------------------------------------------------------------
; Teclado Spectrum (semifilas) + Kempston
; Devuelve A = código, 0 si nada
;   1 X-  2 X+  3 Y-  4 Y+  5 Z+  6 Z-  7 place  8 del  9 clear
;-----------------------------------------------------------------------------
read_key:
        ld      a,$FB               ; Q W E R T
        in      a,($FE)
        bit     0,a
        jr      z,rk_y_minus        ; Q
        bit     1,a
        jr      z,rk_z_plus         ; W
        ld      a,$FD               ; A S D F G
        in      a,($FE)
        bit     0,a
        jr      z,rk_y_plus         ; A
        bit     1,a
        jr      z,rk_z_minus        ; S
        ld      a,$DF               ; P O I U Y
        in      a,($FE)
        bit     0,a
        jr      z,rk_x_plus         ; P
        bit     1,a
        jr      z,rk_x_minus        ; O
        ld      a,$7F               ; SPACE SYM M N B
        in      a,($FE)
        bit     0,a
        jr      z,rk_place
        bit     2,a
        jr      z,rk_del            ; M
        ld      a,$EF               ; 0 9 8 7 6
        in      a,($FE)
        bit     0,a
        jr      z,rk_clear          ; 0
        bit     3,a
        jr      z,rk_y_minus        ; 7 = cursor up (sin CAPS también)
        bit     4,a
        jr      z,rk_y_plus         ; 6 = cursor down
        ld      a,$F7               ; 1 2 3 4 5
        in      a,($FE)
        bit     3,a
        jr      z,rk_x_plus         ; 4
        bit     4,a
        jr      z,rk_x_minus        ; 5
        in      a,($1F)             ; Kempston (si bit 7 está a 1, no hay mando)
        bit     7,a
        jr      nz,rk_none
        bit     0,a
        jr      nz,rk_joy_r
        bit     1,a
        jr      nz,rk_joy_l
        bit     2,a
        jr      nz,rk_joy_d
        bit     3,a
        jr      nz,rk_joy_u
        bit     4,a
        jr      nz,rk_place
rk_none:
        xor     a
        ret
rk_joy_r:
        ld      a,2
        ret
rk_joy_l:
        ld      a,1
        ret
rk_joy_d:
        ld      a,4
        ret
rk_joy_u:
        ld      a,3
        ret
rk_x_minus:
        ld      a,1
        ret
rk_x_plus:
        ld      a,2
        ret
rk_y_minus:
        ld      a,3
        ret
rk_y_plus:
        ld      a,4
        ret
rk_z_plus:
        ld      a,5
        ret
rk_z_minus:
        ld      a,6
        ret
rk_place:
        ld      a,7
        ret
rk_del:
        ld      a,8
        ret
rk_clear:
        ld      a,9
        ret

handle_key:
        cp      1
        jr      z,hk_xm
        cp      2
        jr      z,hk_xp
        cp      3
        jr      z,hk_ym
        cp      4
        jr      z,hk_yp
        cp      5
        jr      z,hk_zp
        cp      6
        jr      z,hk_zm
        cp      7
        jr      z,hk_place
        cp      8
        jr      z,hk_del
        cp      9
        jr      z,hk_clear
        ret
hk_xm:
        ld      a,(cur_x)
        or      a
        ret     z
        dec     a
        ld      (cur_x),a
        ret
hk_xp:
        ld      a,(cur_x)
        cp      GRID_W-1
        ret     nc
        inc     a
        ld      (cur_x),a
        ret
hk_ym:
        ld      a,(cur_y)
        or      a
        ret     z
        dec     a
        ld      (cur_y),a
        ret
hk_yp:
        ld      a,(cur_y)
        cp      GRID_H-1
        ret     nc
        inc     a
        ld      (cur_y),a
        ret
hk_zp:
        ld      a,(cur_z)
        cp      GRID_D-1
        ret     nc
        inc     a
        ld      (cur_z),a
        ret
hk_zm:
        ld      a,(cur_z)
        or      a
        ret     z
        dec     a
        ld      (cur_z),a
        ret
hk_place:
        ld      a,(cur_x)
        ld      b,a
        ld      a,(cur_y)
        ld      c,a
        ld      a,(cur_z)
        ld      e,a
        call    world_addr
        ld      (hl),1
        ret
hk_del:
        ld      a,(cur_x)
        ld      b,a
        ld      a,(cur_y)
        ld      c,a
        ld      a,(cur_z)
        ld      e,a
        call    world_addr
        ld      (hl),0
        ret
hk_clear:
        ld      hl,world
        ld      de,world+1
        ld      (hl),0
        ld      bc,GRID_W*GRID_H*GRID_D-1
        ldir
        ret

;-----------------------------------------------------------------------------
; Dibujo completo: CLS, suelo, ladrillos de atrás hacia adelante, cursor
;-----------------------------------------------------------------------------
draw_world:
        call    cls
        xor     a
        ld      (sum),a
floor_sum:
        ld      b,0                 ; x
floor_x:
        ld      a,(sum)
        sub     b                   ; y = sum-x
        jr      c,floor_xn
        cp      GRID_H
        jr      nc,floor_xn
        ld      c,a
        push    bc
        call    draw_floor
        pop     bc
floor_xn:
        inc     b
        ld      a,b
        cp      GRID_W
        jr      c,floor_x
        ld      hl,sum
        inc     (hl)
        ld      a,(hl)
        cp      GRID_W+GRID_H-1
        jr      c,floor_sum

        xor     a
        ld      (sum),a
brick_sum:
        ld      b,0
brick_x:
        ld      a,(sum)
        sub     b
        jr      c,brick_xn
        cp      GRID_H
        jr      nc,brick_xn
        ld      c,a
        ld      e,0
brick_z:
        push    bc
        push    de
        call    draw_cell
        pop     de
        pop     bc
        inc     e
        ld      a,e
        cp      GRID_D
        jr      c,brick_z
brick_xn:
        inc     b
        ld      a,b
        cp      GRID_W
        jr      c,brick_x
        ld      hl,sum
        inc     (hl)
        ld      a,(hl)
        cp      GRID_W+GRID_H-1
        jr      c,brick_sum
        call    draw_ghost
        ret

draw_cell:
        push    de
        call    world_addr
        ld      a,(hl)
        pop     de
        or      a
        ret     z
        call    project
        call    draw_solid_brick
        ret

draw_floor:
        ld      e,0
        call    project
        ld      a,(brick_sy)
        add     a,16                ; el suelo es la cara inferior de z=0
        ld      (brick_sy),a
        ld      a,PAT_FLOOR
        ld      (pattern),a
        call    fill_top
        ld      a,PAT_EDGE
        ld      (pattern),a
        call    outline_top
        ret

draw_ghost:
        ld      a,(cur_x)
        ld      b,a
        ld      a,(cur_y)
        ld      c,a
        ld      a,(cur_z)
        ld      e,a
        call    project
        ld      a,PAT_GHOST
        ld      (pattern),a
        call    outline_brick
        ret

;-----------------------------------------------------------------------------
; Proyección Filmation 2:1
;   sx = ORIGIN_X + (x - y) * 16
;   sy = ORIGIN_Y + (x + y) *  8 - z * 16
; Entrada: B=x C=y E=z
;-----------------------------------------------------------------------------
project:
        ld      h,0
        ld      l,b
        ld      d,0
        ; DE se usa para y: guardar z
        ld      a,e
        ld      (tmp_z),a
        ld      e,c
        or      a
        sbc     hl,de               ; x-y (con signo)
        add     hl,hl
        add     hl,hl
        add     hl,hl
        add     hl,hl               ; *16
        ld      de,ORIGIN_X
        add     hl,de
        ld      a,l
        ld      (brick_sx),a
        ld      a,b
        add     a,c                 ; x+y
        add     a,a
        add     a,a
        add     a,a                 ; *8
        add     a,ORIGIN_Y
        ld      h,a
        ld      a,(tmp_z)
        add     a,a
        add     a,a
        add     a,a
        add     a,a                 ; z*16
        ld      l,a
        ld      a,h
        sub     l
        ld      (brick_sy),a
        ret

;-----------------------------------------------------------------------------
; Ladrillo sólido: laterales, tapa, aristas
;-----------------------------------------------------------------------------
draw_solid_brick:
        ld      a,PAT_LEFT
        ld      (pattern),a
        call    fill_left
        ld      a,PAT_RIGHT
        ld      (pattern),a
        call    fill_right
        ld      a,PAT_TOP
        ld      (pattern),a
        call    fill_top
        ld      a,PAT_EDGE
        ld      (pattern),a
        call    outline_brick
        ret

; Rombo superior, 16 filas, 2 px por fila (isométrico 2:1)
fill_top:
        xor     a
        ld      (row),a
ft_up:
        ld      a,(brick_sy)
        ld      hl,row
        add     a,(hl)
        ld      d,a                 ; y
        ld      a,(hl)
        add     a,a                 ; 2*r
        ld      e,a
        ld      a,(brick_sx)
        sub     e
        ld      c,a                 ; left
        ld      a,(brick_sx)
        add     a,e
        ld      b,a                 ; right
        call    hline
        ld      hl,row
        inc     (hl)
        ld      a,(hl)
        cp      8
        jr      c,ft_up
        xor     a
        ld      (row),a
ft_dn:
        ld      a,(brick_sy)
        add     a,8
        ld      hl,row
        add     a,(hl)
        ld      d,a
        ld      a,(hl)
        add     a,a
        ld      e,a
        ld      a,(brick_sx)
        sub     16
        add     a,e
        ld      c,a
        ld      a,(brick_sx)
        add     a,16
        sub     e
        ld      b,a
        call    hline
        ld      hl,row
        inc     (hl)
        ld      a,(hl)
        cp      8
        jr      c,ft_dn
        ret

; Paralelogramo izquierdo, lados verticales, 24 filas
fill_left:
        xor     a
        ld      (row),a
fl_loop:
        ld      a,(row)
        cp      8
        jr      c,fl_top
        cp      16
        jr      c,fl_mid
        sub     16
        add     a,a
        ld      e,a
        ld      a,(brick_sx)
        sub     16
        add     a,e
        ld      c,a
        ld      a,(brick_sx)
        ld      b,a
        jr      fl_go
fl_top:
        add     a,a
        ld      e,a
        ld      a,(brick_sx)
        sub     16
        ld      c,a
        add     a,e
        ld      b,a
        jr      fl_go
fl_mid:
        ld      a,(brick_sx)
        sub     16
        ld      c,a
        ld      a,(brick_sx)
        ld      b,a
fl_go:
        ld      a,(brick_sy)
        add     a,8
        ld      hl,row
        add     a,(hl)
        ld      d,a
        call    hline
        ld      hl,row
        inc     (hl)
        ld      a,(hl)
        cp      24
        jr      c,fl_loop
        ret

fill_right:
        xor     a
        ld      (row),a
fr_loop:
        ld      a,(row)
        cp      8
        jr      c,fr_top
        cp      16
        jr      c,fr_mid
        sub     16
        add     a,a
        ld      e,a
        ld      a,(brick_sx)
        ld      c,a
        ld      a,(brick_sx)
        add     a,16
        sub     e
        ld      b,a
        jr      fr_go
fr_top:
        add     a,a
        ld      e,a
        ld      a,(brick_sx)
        add     a,16
        sub     e
        ld      c,a
        ld      a,(brick_sx)
        add     a,16
        ld      b,a
        jr      fr_go
fr_mid:
        ld      a,(brick_sx)
        ld      c,a
        add     a,16
        ld      b,a
fr_go:
        ld      a,(brick_sy)
        add     a,8
        ld      hl,row
        add     a,(hl)
        ld      d,a
        call    hline
        ld      hl,row
        inc     (hl)
        ld      a,(hl)
        cp      24
        jr      c,fr_loop
        ret

outline_top:
        call    edge_nw
        call    edge_ne
        call    edge_ws
        call    edge_es
        ret

outline_brick:
        call    outline_top
        call    edge_w_down
        call    edge_s_down
        call    edge_e_down
        call    edge_w_bot
        call    edge_e_bot
        ret

; Diagonales isométricas de 8 pasos (±2x, +1y) y verticales de 16
edge_nw:
        ld      a,(brick_sx)
        ld      c,a
        ld      a,(brick_sy)
        ld      d,a
        ld      b,8
enw_lp:
        call    plot_edge
        dec     c
        dec     c
        inc     d
        djnz    enw_lp
        ret
edge_ne:
        ld      a,(brick_sx)
        ld      c,a
        ld      a,(brick_sy)
        ld      d,a
        ld      b,8
ene_lp:
        call    plot_edge
        inc     c
        inc     c
        inc     d
        djnz    ene_lp
        ret
edge_ws:
        ld      a,(brick_sx)
        sub     16
        ld      c,a
        ld      a,(brick_sy)
        add     a,8
        ld      d,a
        ld      b,8
ews_lp:
        call    plot_edge
        inc     c
        inc     c
        inc     d
        djnz    ews_lp
        ret
edge_es:
        ld      a,(brick_sx)
        add     a,16
        ld      c,a
        ld      a,(brick_sy)
        add     a,8
        ld      d,a
        ld      b,8
ees_lp:
        call    plot_edge
        dec     c
        dec     c
        inc     d
        djnz    ees_lp
        ret
edge_w_down:
        ld      a,(brick_sx)
        sub     16
        ld      c,a
        ld      a,(brick_sy)
        add     a,8
        ld      d,a
        ld      b,16
ewd_lp:
        call    plot_edge
        inc     d
        djnz    ewd_lp
        ret
edge_e_down:
        ld      a,(brick_sx)
        add     a,16
        ld      c,a
        ld      a,(brick_sy)
        add     a,8
        ld      d,a
        ld      b,16
eed_lp:
        call    plot_edge
        inc     d
        djnz    eed_lp
        ret
edge_s_down:
        ld      a,(brick_sx)
        ld      c,a
        ld      a,(brick_sy)
        add     a,16
        ld      d,a
        ld      b,16
esd_lp:
        call    plot_edge
        inc     d
        djnz    esd_lp
        ret
edge_w_bot:
        ld      a,(brick_sx)
        sub     16
        ld      c,a
        ld      a,(brick_sy)
        add     a,24
        ld      d,a
        ld      b,8
ewb_lp:
        call    plot_edge
        inc     c
        inc     c
        inc     d
        djnz    ewb_lp
        ret
edge_e_bot:
        ld      a,(brick_sx)
        add     a,16
        ld      c,a
        ld      a,(brick_sy)
        add     a,24
        ld      d,a
        ld      b,8
eeb_lp:
        call    plot_edge
        dec     c
        dec     c
        inc     d
        djnz    eeb_lp
        ret

plot_edge:
        push    bc
        push    de
        call    maybe_plot
        pop     de
        pop     bc
        ret

;-----------------------------------------------------------------------------
; HLINE: D=y  C=x0  B=x1 (inclusive)  pattern en (pattern)
;-----------------------------------------------------------------------------
hline:
        ld      a,d
        cp      192
        ret     nc
        ld      a,b
        sub     c
        ret     c
        inc     a
        ld      e,a                 ; count
hl_lp:
        push    bc
        push    de
        call    maybe_plot
        pop     de
        pop     bc
        inc     c
        dec     e
        jr      nz,hl_lp
        ret

; C=x D=y. Aplica patrón y pinta.
maybe_plot:
        ld      a,(pattern)
        or      a
        jr      z,mp_top
        dec     a
        jr      z,mp_left
        dec     a
        jr      z,mp_right
        dec     a
        jr      z,set_pixel         ; PAT_EDGE: siempre
        dec     a
        jr      z,mp_ghost
; PAT_FLOOR: (x+y) & 3 == 0
        ld      a,c
        add     a,d
        and     3
        ret     nz
        jr      set_pixel
mp_top:
        ; 75 %: apaga un pixel de cada 4 en retícula
        ld      a,c
        and     3
        ld      b,a
        ld      a,d
        and     3
        or      b
        jr      nz,set_pixel
        ret
mp_left:
        ; rayas horizontales (cara en sombra)
        ld      a,d
        and     1
        ret     nz
        jr      set_pixel
mp_right:
        ; rayas verticales
        ld      a,c
        and     1
        ret     z
        jr      set_pixel
mp_ghost:
        ld      a,c
        add     a,d
        and     1
        ret     nz
        ; cae a set_pixel

; C=x (0..255) D=y (0..191)
set_pixel:
        ld      a,d
        cp      192
        ret     nc
        and     7
        ld      h,a
        ld      a,d
        rrca
        rrca
        rrca
        and     $18
        or      h
        or      $40
        ld      h,a
        ld      a,d
        and     $38
        add     a,a
        add     a,a
        ld      l,a
        ld      a,c
        rrca
        rrca
        rrca
        and     $1F
        or      l
        ld      l,a
        ld      a,c
        and     7
        ld      de,bitmask
        add     a,e
        ld      e,a
        jr      nc,sp_ok
        inc     d
sp_ok:
        ld      a,(de)
        or      (hl)
        ld      (hl),a
        ret

bitmask:
        db      $80,$40,$20,$10,$08,$04,$02,$01

cls:
        ld      hl,$4000
        ld      de,$4001
        ld      (hl),0
        ld      bc,$17FF
        ldir
        ld      hl,$5800
        ld      de,$5801
        ld      (hl),ATTR_BRICK
        ld      bc,$02FF
        ldir
        ret

;-----------------------------------------------------------------------------
; Variables (dirección fija para el test)
;-----------------------------------------------------------------------------
        ORG     $9C00
done_flag:      db      0
cur_x:          db      0
cur_y:          db      0
cur_z:          db      0
brick_sx:       db      0
brick_sy:       db      0
pattern:        db      0
row:            db      0
sum:            db      0
tmp_z:          db      0
world:          ds      GRID_W*GRID_H*GRID_D

        END
