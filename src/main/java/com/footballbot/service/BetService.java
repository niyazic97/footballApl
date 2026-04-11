package com.footballbot.service;

import com.footballbot.model.BetRecord;
import com.footballbot.repository.BetRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class BetService {

    private final BetRecordRepository betRecordRepository;

    public void saveBet(String matchId, String homeTeam, String awayTeam, String bet, String confidence) {
        boolean skip = bet != null && bet.toLowerCase().contains("пропускаю");
        betRecordRepository.save(BetRecord.builder()
                .matchId(matchId)
                .homeTeam(homeTeam)
                .awayTeam(awayTeam)
                .bet(bet)
                .confidence(confidence)
                .result(skip ? "SKIP" : "PENDING")
                .createdAt(LocalDateTime.now())
                .build());
        log.info("Bet saved for match {}: {} ({})", matchId, bet, confidence);
    }

    /** Called after match finishes — evaluates bet against actual result */
    public void resolveBet(String matchId, int homeScore, int awayScore) {
        betRecordRepository.findById(matchId).ifPresent(bet -> {
            if (!"PENDING".equals(bet.getResult())) return;

            String result = evaluate(bet.getBet(), homeScore, awayScore);
            bet.setResult(result);
            bet.setResolvedAt(LocalDateTime.now());
            betRecordRepository.save(bet);
            log.info("Bet resolved for match {}: {} → {}", matchId, bet.getBet(), result);
        });
    }

    /** Returns "X из Y угадали" string for display */
    public String getStatsLine() {
        var resolved = betRecordRepository.findByResultIn(List.of("WIN", "LOSS"));
        if (resolved.isEmpty()) return "";

        long wins = resolved.stream().filter(b -> "WIN".equals(b.getResult())).count();
        long total = resolved.size();
        return "Статистика прогнозов: " + wins + " из " + total;
    }

    private String evaluate(String bet, int homeScore, int awayScore) {
        if (bet == null) return "LOSS";
        int total = homeScore + awayScore;
        return switch (bet.trim()) {
            case "Победа хозяев"   -> homeScore > awayScore ? "WIN" : "LOSS";
            case "Победа гостей"   -> awayScore > homeScore ? "WIN" : "LOSS";
            case "Ничья"           -> homeScore == awayScore ? "WIN" : "LOSS";
            case "Обе забьют"      -> homeScore > 0 && awayScore > 0 ? "WIN" : "LOSS";
            case "Тотал больше 2.5" -> total > 2 ? "WIN" : "LOSS";
            case "Тотал меньше 2.5" -> total <= 2 ? "WIN" : "LOSS";
            case "Победа хозяев -1" -> homeScore - awayScore >= 2 ? "WIN" : "LOSS";
            case "Победа гостей -1" -> awayScore - homeScore >= 2 ? "WIN" : "LOSS";
            default -> "LOSS";
        };
    }
}
