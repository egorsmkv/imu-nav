#!/usr/bin/env python3
"""Instrument workspace Rust sources, leaving build scripts/dependencies unprofiled.

Cargo calls this through RUSTC_WORKSPACE_WRAPPER. Avoiding dependency instrumentation
also avoids coverage-map collisions in differently configured procedural macros.
"""
import os
from pathlib import Path
import sys

compiler, *arguments = sys.argv[1:]
if '--crate-name' in arguments and arguments[arguments.index('--crate-name') + 1] != 'build_script_build':
    if any(argument.endswith('.rs') and Path(argument).is_file() for argument in arguments):
        arguments += ['-C', 'instrument-coverage', '-Zcoverage-options=branch']
os.execv(compiler, [compiler, *arguments])
