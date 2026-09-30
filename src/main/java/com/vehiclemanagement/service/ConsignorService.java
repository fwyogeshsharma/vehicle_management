package com.vehiclemanagement.service;

import com.vehiclemanagement.domain.Consignor;
import com.vehiclemanagement.exception.ApiException;
import com.vehiclemanagement.exception.ConstraintErrors;
import com.vehiclemanagement.exception.FieldValidationException;
import com.vehiclemanagement.repo.ConsignorRepository;
import com.vehiclemanagement.repo.LorryReceiptRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Customers: the parties at both ends of a consignment.
 *
 * <p>Matching is <b>exact on the lower-cased name</b>, the same rule companies use. "Kumar
 * Traders" and "Kumar Trader" are two customers, and there is no fuzzy match here inventing an
 * opinion about whether they are the same firm. What the UI does instead is show what already
 * exists while the clerk types, so the second one is noticed before it is created.
 */
@Service
public class ConsignorService {

    private final ConsignorRepository consignors;
    private final LorryReceiptRepository receipts;

    public ConsignorService(ConsignorRepository consignors, LorryReceiptRepository receipts) {
        this.consignors = consignors;
        this.receipts = receipts;
    }

    public Page<Consignor> list(String q, Boolean active, Pageable pageable) {
        String like = Normalizer.clean(q) == null ? null : "%" + q.trim() + "%";
        return consignors.search(like, active, pageable);
    }

    public List<Consignor> listActive() {
        return consignors.findByActiveTrueOrderByNameAsc();
    }

    public Consignor get(long id) {
        return consignors.findById(id).orElseThrow(
                () -> new ApiException.NotFound("Customer " + id + " not found."));
    }

    @Transactional
    public Consignor create(String name, String mobile, String address, String gstin) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "A customer needs a name.");
        }
        consignors.findByNameKey(clean.toLowerCase()).ifPresent(existing -> {
            throw new ApiException.Conflict("A customer called " + existing.getName()
                    + " already exists"
                    + (existing.isActive() ? "." : ", retired. Restore it instead."));
        });
        Consignor c = new Consignor(clean, Normalizer.optionalMobile(mobile, "mobile"));
        c.setAddress(Normalizer.clean(address));
        c.setGstin(gstin(gstin));
        return ConstraintErrors.translating(() -> consignors.saveAndFlush(c));
    }

    /**
     * Find-or-create on the lower-cased name, used while saving an LR.
     *
     * <p>A mobile typed on the receipt is only written back when the customer has none on
     * file. An existing number is left alone: the clerk is recording who to ring about
     * <i>this load</i>, which is not the same as correcting the customer's record, and letting
     * one silently overwrite the other is how a master gets quietly wrong.
     */
    @Transactional
    public Consignor ensure(String name, String mobile) {
        String clean = Normalizer.clean(name);
        if (clean == null) {
            return null;
        }
        return consignors.findByNameKey(clean.toLowerCase())
                .map(existing -> {
                    if (existing.getMobile() == null && mobile != null) {
                        existing.setMobile(mobile);
                    }
                    return existing;
                })
                .orElseGet(() -> consignors.save(new Consignor(clean, mobile)));
    }

    @Transactional
    public Consignor update(long id, String name, String mobile, String address, String gstin) {
        Consignor c = get(id);
        String clean = Normalizer.clean(name);
        if (clean == null) {
            throw new FieldValidationException("name", "A customer needs a name.");
        }
        if (consignors.existsByNameKeyAndIdNot(clean.toLowerCase(), id)) {
            throw new ApiException.Conflict(
                    "Another customer is already called " + clean + ".");
        }
        // Renaming does NOT touch the lorry receipts already issued to this customer. Their
        // consignor_name is the name that was printed and agreed, and rewriting history to
        // match a correction made today would change documents the customer holds a copy of.
        c.setName(clean);
        c.setMobile(Normalizer.optionalMobile(mobile, "mobile"));
        c.setAddress(Normalizer.clean(address));
        c.setGstin(gstin(gstin));
        return ConstraintErrors.translating(() -> consignors.saveAndFlush(c));
    }

    /**
     * Retire a customer. Never a delete.
     *
     * <p>Lorry receipts reference this row ON DELETE SET NULL, so a delete would silently strip
     * the link off every historic receipt for this customer — the text snapshot would survive,
     * but "show me everything we carried for them" would quietly return less each time someone
     * tidied the list.
     */
    @Transactional
    public Consignor retire(long id) {
        Consignor c = get(id);
        c.setActive(false);
        return c;
    }

    @Transactional
    public Consignor restore(long id) {
        Consignor c = get(id);
        c.setActive(true);
        return c;
    }

    /** How many receipts name this customer at either end. Shown before retiring one. */
    public long receiptCount(long id) {
        return receipts.search(null, null, null, null, null, null, id, null,
                org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
    }

    private static String gstin(String raw) {
        String clean = Normalizer.clean(raw);
        return clean == null ? null : clean.toUpperCase();
    }
}
