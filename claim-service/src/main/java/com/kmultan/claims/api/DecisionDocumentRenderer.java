package com.kmultan.claims.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import com.kmultan.claims.domain.Claim;
import com.kmultan.claims.domain.ClaimStatus;
import com.kmultan.claims.domain.Policy;

/**
 * The formal decision letter as a one-page PDF: what was decided, on which
 * policy basis, and how the payable amount was derived. A view concern of the
 * web layer — the domain knows nothing about documents.
 */
@Component
public class DecisionDocumentRenderer {

    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    public boolean hasDecision(Claim claim) {
        return switch (claim.getStatus()) {
            case APPROVED, PENDING_SECOND_APPROVAL, PARTIALLY_PAID, PAID, PAYOUT_FAILED, REJECTED -> true;
            case SUBMITTED, PENDING_REVIEW, WITHDRAWN -> false;
        };
    }

    public byte[] render(Claim claim, Policy policy) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float cursorY = 800;
                cursorY = line(content, BOLD, 16, 50, cursorY, "Claims Platform Insurance");
                cursorY = line(content, REGULAR, 9, 50, cursorY - 2, "Decision issued on " + LocalDate.now());
                cursorY -= 18;
                cursorY = line(content, BOLD, 13, 50, cursorY, "Decision on claim " + claim.getClaimNumber());
                cursorY -= 8;
                cursorY = line(
                        content,
                        REGULAR,
                        10,
                        50,
                        cursorY,
                        "Policy: " + claim.getPolicyNumber()
                                + (policy != null
                                        ? "  (" + policy.getCoverageType() + ", valid " + policy.getValidFrom() + " to "
                                                + policy.getValidTo() + ")"
                                        : ""));
                cursorY = line(
                        content,
                        REGULAR,
                        10,
                        50,
                        cursorY,
                        "Vehicle: " + claim.getPlateNumber() + "    Incident date: " + claim.getIncidentDate());
                cursorY -= 14;
                for (String paragraph : decisionText(claim)) {
                    cursorY = line(content, REGULAR, 11, 50, cursorY, paragraph);
                }
                if (claim.getStatus() != ClaimStatus.REJECTED && claim.getGrossApprovedAmount() != null) {
                    cursorY -= 14;
                    cursorY = line(content, BOLD, 11, 50, cursorY, "Settlement");
                    cursorY = amountLine(content, cursorY, "Awarded amount", claim.getGrossApprovedAmount());
                    if (policy != null && claim.getGrossApprovedAmount().compareTo(policy.getSumInsured()) > 0) {
                        cursorY = amountLine(content, cursorY, "Capped at the sum insured", policy.getSumInsured());
                    }
                    cursorY = amountLine(content, cursorY, "Deductible", claim.getDeductibleApplied());
                    cursorY = amountLine(content, cursorY, "Amount payable", claim.getPayableAmount());
                    cursorY = amountLine(content, cursorY, "Paid to date", claim.getPaidAmount());
                }
                cursorY -= 24;
                cursorY = line(
                        content,
                        REGULAR,
                        9,
                        50,
                        cursorY,
                        "You may appeal this decision in writing within 30 days of receiving it.");
                line(
                        content,
                        REGULAR,
                        9,
                        50,
                        cursorY,
                        "This document was generated electronically and is valid without a signature.");
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not render the decision document", exception);
        }
    }

    private List<String> decisionText(Claim claim) {
        List<String> paragraphs = new ArrayList<>();
        switch (claim.getStatus()) {
            case REJECTED -> {
                paragraphs.add("After reviewing the claim we are unable to accept it.");
                paragraphs.add("Reason: " + claim.getRejectionReason());
            }
            case PENDING_SECOND_APPROVAL -> paragraphs.add(
                    "The claim has been positively assessed and awaits a confirming second approval.");
            default -> {
                paragraphs.add("After reviewing the claim we decided to accept it and pay compensation");
                paragraphs.add("as broken down below.");
            }
        }
        return paragraphs;
    }

    private float amountLine(PDPageContentStream content, float cursorY, String label, BigDecimal amount)
            throws IOException {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return line(content, REGULAR, 10, 60, cursorY, String.format(Locale.UK, "%-28s %,15.2f PLN", label, value));
    }

    private float line(
            PDPageContentStream content, PDType1Font font, int size, float offsetX, float cursorY, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(offsetX, cursorY);
        content.showText(text);
        content.endText();
        return cursorY - size - 5;
    }
}
