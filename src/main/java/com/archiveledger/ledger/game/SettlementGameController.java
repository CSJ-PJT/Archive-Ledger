package com.archiveledger.ledger.game;

import com.archiveledger.ledger.game.SettlementGameModels.SettlementGameRequest;
import com.archiveledger.ledger.game.SettlementGameModels.SettlementGameResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/game/settlement-agency")
public class SettlementGameController {
    private final SettlementGameService service;
    public SettlementGameController(SettlementGameService service) { this.service = service; }

    @GetMapping("/preset")
    SettlementGameRequest preset() { return service.defaultPreset(); }

    @PostMapping("/simulate")
    SettlementGameResponse simulate(@RequestBody(required = false) SettlementGameRequest request) {
        return service.simulate(request);
    }
}
