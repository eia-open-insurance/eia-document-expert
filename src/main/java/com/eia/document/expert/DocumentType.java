package com.eia.document.expert;

import java.util.Arrays;
import java.util.Optional;

/**
 * Document types from SPEC section 11.6.5. Enumerations are stored as
 * {@code varchar} per SPEC section 10.4, so the wire value is the enum name.
 */
public enum DocumentType {
    POLICY,
    DECLARATIONS,
    ENDORSEMENT,
    CERTIFICATE,
    BINDER,
    QUOTE,
    SLIP,
    WORDING,
    FORM,
    EXCLUSION,
    SCHEDULE,
    ASSUMED_CONTRACT,
    TREATY_SLIP,
    BORDEREAU,
    LOSS_ADVICE,
    TECHNICAL_ACCOUNT,
    STATEMENT,
    CASH_CALL,
    SECURITY,
    COMMUTATION_AGREEMENT,
    CLAIM_FORM,
    FNOL,
    PROOF_OF_LOSS,
    PLAINTIFF_FACT_SHEET,
    MEDICAL_RECORD,
    MEDICAL_BILL,
    B_READ,
    EXPOSURE_AFFIDAVIT,
    AUTHORIZATION,
    HIPAA_RELEASE,
    DEMAND_LETTER,
    PLEADING,
    DISCOVERY,
    DEPOSITION,
    EXPERT_REPORT,
    SETTLEMENT_AGREEMENT,
    RELEASE,
    CONSENT_JUDGMENT,
    COURT_ORDER,
    LIEN,
    LIEN_WAIVER,
    MEDICARE_CONDITIONAL_PAYMENT,
    TRUST_CLAIM_FORM,
    TRUST_DISTRIBUTION,
    PAYMENT_AUTHORITY,
    PAYMENT_RECEIPT,
    REMITTANCE,
    INVOICE,
    DEFENSE_INVOICE,
    TAX_FORM,
    W9,
    KYC,
    SANCTIONS_RESULT,
    CORRESPONDENCE,
    EMAIL,
    NOTE,
    PHOTO,
    AUDIO,
    VIDEO,
    OTHER;

    public static Optional<DocumentType> find(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.name().equals(raw)).findFirst();
    }

    public static String names() {
        return String.join(", ", Arrays.stream(values()).map(Enum::name).toList());
    }
}
