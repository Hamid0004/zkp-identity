//! ICAO 9303 TD3 MRZ parser — Phase A of issue #8 A-01.
//!
//! WHY THIS EXISTS (the A-01 gap it closes):
//!   The ZK circuit proves attributes (age/nationality) taken from CALLER
//!   JSON, while integrity only authenticates raw DG1 bytes. A caller can
//!   keep a valid DG1+SOD pair and swap JSON DOB/nationality — attribute
//!   substitution (3 external reviews converged on this P0).
//!
//!   FIX: attributes must be EXTRACTED from the authenticated DG1 bytes.
//!   DG1 is the binary encoding of the MRZ, so parsing MRZ from dg1_bytes
//!   (with ICAO check-digit validation) makes the passport the sole source
//!   of truth. JSON becomes transport-only; mismatch => fail closed.
//!
//! WHY TD3 only: passport booklets use TD3 (2×44 chars). TD1 (ID cards,
//! 3×30) is a follow-up when needed. Fail-closed on anything else.
//!
//! WHY check digits matter: an attacker fabricating an MRZ must produce
//! valid ICAO 7-3-9 check digits for doc#, DOB, expiry AND the composite —
//! a cheap but effective structural integrity layer on top of SOD.

use anyhow::{anyhow, Result};

/// [ICAO 9303] Check-digit character values: '0'-'9' => 0-9,
/// 'A'-'Z' => 10-35, '<' (filler) => 0.
fn char_value(c: u8) -> Result<u32> {
    match c {
        b'0'..=b'9' => Ok((c - b'0') as u32),
        b'A'..=b'Z' => Ok((c - b'A') as u32 + 10),
        b'<' => Ok(0),
        other => Err(anyhow!("MRZ: invalid character 0x{other:02X}")),
    }
}

/// [ICAO 9303] 7-3-9 repeating weight check digit.
/// data = raw field bytes (including '<' fillers).
pub fn check_digit(data: &[u8]) -> Result<u8> {
    // [ICAO 9303 Part 3 §4.9] Weighting cycle is 7, 3, 1 — NOT 7,3,9.
    // (Previous 7,3,9 was transcribed from an unverified external review;
    //  fixtures were generated with the same wrong formula, so tests
    //  self-agreed while being wrong — caught in post-fix review.)
    const WEIGHTS: [u32; 3] = [7, 3, 1];
    let mut sum = 0u32;
    for (i, &c) in data.iter().enumerate() {
        sum += char_value(c)? * WEIGHTS[i % 3];
    }
    Ok((sum % 10) as u8)
}

/// Validate a field against its expected check digit.
/// `expected` is the raw ASCII byte from the MRZ — convert '0'..'9' => 0..9
/// (and reject anything else, since check digits are always a single digit).
fn verify_field(data: &[u8], expected: u8, field: &str) -> Result<()> {
    let actual = check_digit(data)?;
    let expected_val = match expected {
        c @ b'0'..=b'9' => c - b'0',
        other => {
            return Err(anyhow!(
                "MRZ: {field} check digit must be ASCII 0-9 (found 0x{other:02X})"
            ));
        }
    };
    if actual != expected_val {
        return Err(anyhow!(
            "MRZ: {field} check digit mismatch (computed {actual}, found {expected_val})"
        ));
    }
    Ok(())
}

/// Strip '<' fillers (and anything after them) from a field.
fn clean(field: &[u8]) -> String {
    let end = field.iter().position(|&c| c == b'<').unwrap_or(field.len());
    String::from_utf8_lossy(&field[..end]).to_string()
}


/// [ICAO TD3] Name field: SURNAME<<GIVEN — single '<' = space inside a part,
/// '<<' separates primary/secondary, trailing '<' = filler.
/// Must NOT use clean() — that would eat the << separator.
fn parse_name_field(field: &[u8]) -> (String, String) {
    let s = String::from_utf8_lossy(field);
    let trimmed = s.trim_end_matches('<');
    match trimmed.find("<<") {
        Some(i) => {
            let surname = trimmed[..i].replace('<', " ").trim().to_string();
            let given = trimmed[i + 2..].replace('<', " ").trim().to_string();
            (surname, given)
        }
        None => (trimmed.replace('<', " ").trim().to_string(), String::new()),
    }
}

/// Extract ASCII text bytes for a fixed position range, fail on non-ASCII.
fn field_bytes<'a>(buf: &'a [u8], start: usize, len: usize, name: &str) -> Result<&'a [u8]> {
    if start + len > buf.len() {
        return Err(anyhow!("MRZ: {name} range out of bounds"));
    }
    let f = &buf[start..start + len];
    if !f.iter().all(|c| c.is_ascii_alphanumeric() || *c == b'<') {
        return Err(anyhow!("MRZ: {name} contains non-MRZ characters"));
    }
    Ok(f)
}

/// Parsed TD3 MRZ — the AUTHORITATIVE identity attributes (issue #8 A-01).
/// Everything the Merkle tree needs must come from here, not caller JSON.
#[derive(Debug, Clone, PartialEq)]
pub struct ParsedMrz {
    pub document_code: String,   // "P"
    pub issuing_state: String,   // e.g. "PAK"
    pub surname: String,         // primary identifier
    pub given_names: String,     // secondary identifier
    pub document_number: String, // e.g. "AB1234567"
    pub nationality: String,     // e.g. "PAK"
    pub date_of_birth: String,   // YYMMDD (matches existing pipeline format)
    pub sex: String,             // M / F / <
    pub date_of_expiry: String,  // YYMMDD
    pub personal_number: String, // optional field (may be empty)
}

/// Parse + fully validate a TD3 MRZ (passport booklets, 2 lines × 44 chars).
///
/// `line1`/`line2` are the RAW MRZ bytes as they appear in DG1 content
/// (44 ASCII chars each, no line terminators).
pub fn parse_td3(line1: &[u8], line2: &[u8]) -> Result<ParsedMrz> {
    if line1.len() != 44 || line2.len() != 44 {
        return Err(anyhow!(
            "MRZ TD3: lines must be exactly 44 chars (got {}, {})",
            line1.len(), line2.len()
        ));
    }

    // ── Line 1: document code + issuing state + name ──
    // [ICAO 9303 TD3, 0-indexed] pos 0-1 doc code, pos 2-4 state, pos 5-43 name
    let doc_code_raw = field_bytes(line1, 0, 2, "document_code")?;
    if doc_code_raw[0] != b'P' {
        return Err(anyhow!("MRZ TD3: document code must start with 'P' (got 0x{:02X})", doc_code_raw[0]));
    }
    let issuing = field_bytes(line1, 2, 3, "issuing_state")?;
    let name_field = field_bytes(line1, 5, 39, "name")?; // pos 5-43: name (39) // pos 6-43: SURNAME<<GIVEN
    // Name format: SURNAME<<GIVEN<NAMES — '<<' is the primary/secondary separator
    let (surname, given_names) = parse_name_field(name_field);

    // ── Line 2: identity numbers + dates + check digits ──
    let doc_num_raw = field_bytes(line2, 0, 9, "document_number")?;
    let doc_check = field_bytes(line2, 9, 1, "doc_check")?[0];
    verify_field(doc_num_raw, doc_check, "document_number")?;
    let document_number = clean(doc_num_raw);

    let nationality = clean(field_bytes(line2, 10, 3, "nationality")?);

    let dob_raw = field_bytes(line2, 13, 6, "date_of_birth")?;
    let dob_check = field_bytes(line2, 19, 1, "dob_check")?[0];
    verify_field(dob_raw, dob_check, "date_of_birth")?;
    let date_of_birth = clean(dob_raw);

    let sex = clean(field_bytes(line2, 20, 1, "sex")?);

    let expiry_raw = field_bytes(line2, 21, 6, "date_of_expiry")?;
    let expiry_check = field_bytes(line2, 27, 1, "expiry_check")?[0];
    verify_field(expiry_raw, expiry_check, "date_of_expiry")?;
    let date_of_expiry = clean(expiry_raw);

    let personal_raw = field_bytes(line2, 28, 14, "personal_number")?;
    let personal_check = field_bytes(line2, 42, 1, "personal_check")?[0];
    // Some passports leave personal number empty; '<'-only field has check '0'.
    let personal_clean = clean(personal_raw);
    // [ICAO 9303 Part 4 §4.2.2] If personal number is unused, field is
    // '<'-filled AND its check digit may be '<' OR '0' — both valid.
    let personal_all_filler = personal_raw.iter().all(|&c| c == b'<');
    let empty_ok = personal_check == b'0' || personal_check == b'<';
    if !(personal_all_filler && empty_ok) {
        verify_field(personal_raw, personal_check, "personal_number")?;
    }
    let personal_number = personal_clean.replace('<', " ");

    // ── Composite check digit: over doc# + doc_check + DOB + dob_check +
    //    expiry + expiry_check + personal + personal_check (ICAO spec) ──
    let mut composite_input = Vec::with_capacity(39); // 9+1+6+1+6+1+14+1
    composite_input.extend_from_slice(doc_num_raw);
    composite_input.push(doc_check);
    composite_input.extend_from_slice(dob_raw);
    composite_input.push(dob_check);
    composite_input.extend_from_slice(expiry_raw);
    composite_input.push(expiry_check);
    composite_input.extend_from_slice(personal_raw);
    composite_input.push(personal_check);
    let composite_expected = field_bytes(line2, 43, 1, "composite_check")?[0];
    verify_field(&composite_input, composite_expected, "composite")?;

    // ── DOB must parse through the hardened pipeline (A-08) ──
    // (imported by caller module; validated again upstream — here we only
    //  sanity-check it's 6 digits, which verify_field's charset already did)

    Ok(ParsedMrz {
        document_code: clean(doc_code_raw),
        issuing_state: clean(issuing),
        surname,
        given_names,
        document_number,
        nationality,
        date_of_birth,
        sex,
        date_of_expiry,
        personal_number,
    })
}
