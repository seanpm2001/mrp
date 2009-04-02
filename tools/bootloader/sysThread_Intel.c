/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */

/*
 * Architecture specific thread code
 */

#include "sys.h"

#ifdef _WIN32
/* disable warning C4731: frame pointer register 'ebp' modified by
   inline assembly code */
#pragma warning(disable: 4731)
#endif

/**
 * Transfer execution from C to Java for thread startup
 */
void bootThread (void *_ip, void *_tr, void *_sp, void UNUSED *_jtoc)
{
  void *saved_ebp;
#ifndef _WIN32
  asm volatile (
#ifndef __x86_64__
       "mov   %%ebp, %0     \n"
       "mov   %%esp, %%ebp  \n"
       "mov   %3, %%esp     \n"
       "push  %%ebp         \n"
       "call  *%%eax        \n"
       "pop   %%esp         \n"
       "mov   %0, %%ebp     \n"
#else
       "mov   %%rbp, %0     \n"
       "mov   %%rsp, %%rbp  \n"
       "mov   %3, %%rsp     \n"
       "push  %%rbp         \n"
       "call  *%%rax        \n"
       "pop   %%rsp         \n"
       "mov   %0, %%rbp     \n"
#endif
       : "=m"(saved_ebp)
       : "a"(_ip), // EAX = Instruction Pointer
	 "S"(_tr), // ESI = Thread Register
	 "r"(_sp)
       );
#else
  __asm{
      mov eax, _ip
      mov esi, _tr
      mov saved_ebp, ebp
      mov ebp, esp
      mov esp, _sp
      push ebp
      call [eax]
      pop esp
      mov ebp, saved_ebp
  }
#endif
}