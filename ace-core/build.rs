fn main() {
    uniffi::generate_scaffolding("src/ace_core.udl")
        .expect("UniFFI scaffolding generation failed");
}
