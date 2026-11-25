package com.vehiclemanagement.web;

import com.vehiclemanagement.domain.VehicleIntake;
import com.vehiclemanagement.repo.UserRepository;
import com.vehiclemanagement.security.Roles;
import com.vehiclemanagement.service.IntakeService;
import com.vehiclemanagement.web.dto.IntakeDtos;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Who has added how many intake entries, and on which days. Administrators only.
 *
 * <p>Counts what is still in {@code vehicle_intake}: a discarded entry is deleted outright, so it
 * is not here to count. Days are calendar days in the caller's time zone ({@code tz}, defaulting
 * to Asia/Kolkata), so "today" on the screen is today for the person reading it.
 */
@RestController
@RequestMapping("/api/intake/activity")
@PreAuthorize(Roles.ADMIN)
public class IntakeActivityController {

    private final IntakeService intake;
    private final UserRepository users;

    public IntakeActivityController(IntakeService intake, UserRepository users) {
        this.intake = intake;
        this.users = users;
    }

    @Operation(summary = "Everyone who has added intake entries, with their totals")
    @GetMapping("/uploaders")
    public List<IntakeDtos.Uploader> uploaders() {
        return intake.uploaders().stream()
                .map(u -> new IntakeDtos.Uploader(u.getUserId(), u.getName(), u.getTotal()))
                .toList();
    }

    @Operation(summary = "One user's entries per day, newest day first")
    @GetMapping("/daily")
    public IntakeDtos.DailyCounts daily(
            @RequestParam(name = "user_id") long userId,
            @RequestParam(name = "tz", required = false) String tz) {
        var days = intake.dailyCounts(userId, tz).stream()
                .map(d -> new IntakeDtos.Day(d.getDay().toLocalDate(), d.getTotal()))
                .toList();
        return new IntakeDtos.DailyCounts(days.stream().mapToLong(IntakeDtos.Day::count).sum(), days);
    }

    @Operation(summary = "Every entry one user added on one day, newest first")
    @GetMapping("/entries")
    public List<IntakeDtos.Summary> entries(
            @RequestParam(name = "user_id") long userId,
            @RequestParam(name = "date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "tz", required = false) String tz) {
        List<VehicleIntake> rows = intake.entriesOn(userId, date, tz);
        var names = users.namesById(rows.stream().map(VehicleIntake::getUploadedBy).toList());
        var notes = intake.notesFor(rows.stream().map(VehicleIntake::getId).toList());
        return rows.stream()
                .map(i -> IntakeDtos.Summary.from(i, names.get(i.getUploadedBy()),
                        notes.getOrDefault(i.getId(), List.of())))
                .toList();
    }
}
