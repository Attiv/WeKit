// art/trampoline.rs — dual-mapped trampoline pool
// Aligns with art_hook.cpp TrampolinePool (lines 780-897)

use crate::loge;
use libc::c_int;
use std::sync::atomic::{AtomicUsize, Ordering};

const POOL_SIZE: usize = 1024 * 1024; // 1 MB
const TRAMPOLINE_STRIDE: usize = 32;

pub struct TrampolinePool {
    writable: *mut u8,     // PROT_READ|PROT_WRITE, MAP_SHARED
    executable: *const u8, // PROT_READ|PROT_EXEC, MAP_SHARED
    next_slot: AtomicUsize,
}

// SAFETY: the dual-mapped memfd makes both pointers safe to use from any thread
unsafe impl Send for TrampolinePool {}
unsafe impl Sync for TrampolinePool {}

impl TrampolinePool {
    pub fn new() -> Option<Self> {
        unsafe {
            let mfd = libc::syscall(
                libc::SYS_memfd_create,
                c"jit-cache".as_ptr(),
                libc::MFD_CLOEXEC as libc::c_ulong,
            ) as c_int;
            if mfd < 0 {
                loge!("Zygisk: memfd_create failed");
                return None;
            }
            if libc::ftruncate(mfd, POOL_SIZE as libc::off_t) < 0 {
                libc::close(mfd);
                return None;
            }
            let writable = libc::mmap(
                std::ptr::null_mut(),
                POOL_SIZE,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                mfd,
                0,
            );
            let executable = libc::mmap(
                std::ptr::null_mut(),
                POOL_SIZE,
                libc::PROT_READ | libc::PROT_EXEC,
                libc::MAP_SHARED,
                mfd,
                0,
            );
            libc::close(mfd);
            if writable == libc::MAP_FAILED || executable == libc::MAP_FAILED {
                if writable != libc::MAP_FAILED {
                    libc::munmap(writable, POOL_SIZE);
                }
                if executable != libc::MAP_FAILED {
                    libc::munmap(executable as *mut _, POOL_SIZE);
                }
                return None;
            }
            Some(TrampolinePool {
                writable: writable as *mut u8,
                executable: executable as *const u8,
                next_slot: AtomicUsize::new(0),
            })
        }
    }

    /// Allocate one trampoline slot and return its executable address.
    pub fn allocate(&self, bridge_art_method: usize, entry_point_offset: usize) -> *const u8 {
        let slot = self
            .next_slot
            .fetch_add(TRAMPOLINE_STRIDE, Ordering::Relaxed);
        if slot + TRAMPOLINE_STRIDE > POOL_SIZE {
            loge!("Zygisk: trampoline pool exhausted");
            return std::ptr::null();
        }
        let w = unsafe { self.writable.add(slot) };
        let exec = unsafe { self.executable.add(slot) };
        // SAFETY: we write through writable alias, execute through executable alias
        unsafe {
            write_trampoline(w, bridge_art_method, entry_point_offset);
            flush_icache(exec, exec.add(TRAMPOLINE_STRIDE));
        }
        exec
    }
}

// ── arm64 trampoline (20 bytes, padded to 32) ─────────────────────────────────
// ldr x0, #12          ; load bridge_art_method (8-byte literal at +12)
// ldur x16, [x0, #ep]  ; load quick entry point from bridge ArtMethod
// br x16
// nop (4 bytes padding)
// .8byte bridge_art_method

#[cfg(target_arch = "aarch64")]
unsafe fn write_trampoline(dst: *mut u8, bridge_art_method: usize, ep_offset: usize) {
    let ep = (ep_offset & 0x1FF) as u32;
    // ldur x16, [x0, #ep_offset]: 0xF8400210 | (imm9 << 12)
    let ldur_x16 = 0xF840_0210u32 | (ep << 12);
    let code: [u32; 4] = [
        0x5800_0060, // ldr x0, #12
        ldur_x16,    // ldur x16, [x0, #ep_offset]
        0xD61F_0200, // br x16
        0xD503_201F, // nop (padding)
    ];
    // Write 4 x u32 = 16 bytes at dst
    (dst as *mut [u32; 4]).write_unaligned(code);
    // Write 8-byte bridge pointer at offset +12 (overlaps nop slot and extends into next slot)
    // Actually for arm64: ldr x0, #12 loads from PC+12. The instruction is at offset 0,
    // so PC+12 = offset 12. We need to place the 8-byte value at offset 12.
    // But we already wrote a u32 nop at offset 12 above. Let's fix the layout:
    // offset 0:  ldr x0, #12    (4 bytes) — loads the 8-byte literal at PC+12 = offset 12
    // offset 4:  ldur x16, ...  (4 bytes)
    // offset 8:  br x16         (4 bytes)
    // offset 12: bridge_art_method (8 bytes) — literal data
    // offset 20: (empty, up to 32)
    // Re-write correctly: first 3 instructions, then 8-byte pointer at offset 12
    let instr: [u32; 3] = [0x5800_0060, ldur_x16, 0xD61F_0200];
    (dst as *mut [u32; 3]).write_unaligned(instr);
    let ptr_off = dst.add(12) as *mut usize;
    ptr_off.write_unaligned(bridge_art_method);
}

// ── arm32 trampoline (12 bytes) ───────────────────────────────────────────────
// ldr r0, [pc, #0]              ; load bridge_art_method
// ldr pc, [r0, #ep_offset]      ; jump to bridge entry point
// .word bridge_art_method

#[cfg(target_arch = "arm")]
unsafe fn write_trampoline(dst: *mut u8, bridge_art_method: usize, ep_offset: usize) {
    let ep = (ep_offset & 0xFFF) as u32;
    let ldr_pc = 0xE590_F000u32 | ep; // ldr pc, [r0, #ep_offset]
    let code: [u32; 3] = [
        0xE59F_0000, // ldr r0, [pc, #0]
        ldr_pc,
        bridge_art_method as u32,
    ];
    (dst as *mut [u32; 3]).write_unaligned(code);
}

#[cfg(not(any(target_arch = "aarch64", target_arch = "arm")))]
unsafe fn write_trampoline(_dst: *mut u8, _bridge: usize, _ep: usize) {}

#[cfg(any(target_arch = "aarch64", target_arch = "arm"))]
unsafe fn flush_icache(start: *const u8, end: *const u8) {
    unsafe extern "C" {
        fn __clear_cache(start: *const u8, end: *const u8);
    }
    unsafe { __clear_cache(start, end) };
}

#[cfg(not(any(target_arch = "aarch64", target_arch = "arm")))]
unsafe fn flush_icache(_start: *const u8, _end: *const u8) {}
