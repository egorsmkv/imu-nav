# Native core verification

Kani checks selected Rust navigation rules over many bounded inputs. It complements tests and replay; it does not prove that the whole app is correct. IMU Nav remains a research prototype, not a safety system.

## Run the checks

1. On Linux x86-64, install the pinned Kani version and its compiler and solver:

   ```bash
   cargo install --locked kani-verifier --version 0.68.0
   cargo kani setup
   ```

2. Run the validation tests and proofs from the project root:

   ```bash
   python3 -m unittest discover -s tools/tests -v
   python3 tools/verify_native.py
   ```

3. Read the summary and any failed proof's counterexample. A failed proof needs investigation; changing a bound or disabling a check does not by itself fix the code.

The complete run has a 40-minute deadline and uses bounded proofs. The [detailed verification reference](reference/NATIVE_VERIFICATION.md) lists every contract, limit and debugging command. See the [glossary](GLOSSARY.md) for Kani, proof and bound.
