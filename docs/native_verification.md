# Native core verification

[Documentation index](../README.md)

Kani checks selected Rust navigation rules over many bounded inputs. It complements tests and replay; it does not prove that the whole app is correct.

## Run the checks

1. On Linux x86-64, install Kani with the same pinned compiler used by the workflow:

   ```bash
   rustup toolchain install nightly-2026-08-21 --profile minimal
   cargo +nightly-2026-08-21 install --locked kani-verifier --version 0.68.0
   cargo kani setup
   ```

2. Run the validation tests and proofs from the project root:

   ```bash
   python3 -m unittest discover -s tools/tests -v
   python3 tools/verify_native.py
   ```

3. Read the summary and any failed proof's counterexample. A failed proof needs investigation; changing a bound or disabling a check does not by itself fix the code.

The complete run has a 40-minute deadline and uses bounded proofs. The [detailed verification reference](reference/native_verification.md) lists every contract, limit and debugging command. See the [glossary](glossary.md) for Kani, proof and bound.

The [Native formal verification workflow](../.github/workflows/native-verification.yml) runs only
when manually dispatched. It is not an automatic pull-request check. A passing Rust test run,
including the geometry fixture test, does not mean Kani proofs ran; check the runner's
`build/native-verification/summary.json` for that run's result.
