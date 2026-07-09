# Archive Suite Ledger Demo Scenario

## 1. 실행

```powershell
cd C:\Users\dan18\Documents\ArchivePJT\Archive-Nexus
docker compose up --build -d
```

## 2. Nexus synthetic event 생성

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/outbox/events/generate?count=1000"
```

## 3. Outbox publish

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/outbox/events/publish"
```

## 4. Ledger 수신 확인

```powershell
Invoke-RestMethod "http://localhost:18080/api/events/received"
```

## 5. 거래와 원장 확인

```powershell
Invoke-RestMethod "http://localhost:18080/api/transactions"
Invoke-RestMethod "http://localhost:18080/api/ledger/entries"
```

## 6. Approval required 거래 확인

```powershell
Invoke-RestMethod "http://localhost:18080/api/transactions?status=APPROVAL_REQUIRED"
```

## 7. ArchiveOS 승인/반려

```powershell
Invoke-RestMethod "http://localhost:4000/api/approvals/external"
```

PM/Admin 권한이 필요한 환경에서는 ArchiveOS UI 또는 세션 인증 후 approve/reject API를 호출한다.

## 8. Ledger callback 확인

```powershell
Invoke-RestMethod -Method Post "http://localhost:18080/api/approvals/callback" `
  -ContentType "application/json" `
  -Body '{"approvalRequestId":"APR-SYNTHETIC","transactionId":"TX-YYYYMMDD-XXXX","decision":"APPROVED","decidedBy":"synthetic-operator","comment":"Approved after policy review"}'
```

## 9. Daily settlement 실행

```powershell
$date = Get-Date -Format "yyyy-MM-dd"
Invoke-RestMethod -Method Post "http://localhost:18080/api/settlements/daily/run?date=$date"
```

## 10. Reconciliation 확인

```powershell
$date = Get-Date -Format "yyyy-MM-dd"
Invoke-RestMethod -Method Post "http://localhost:18080/api/reconciliation/daily?date=$date"
Invoke-RestMethod "http://localhost:18080/api/operations/summary"
```

## 11. Health / metrics

```powershell
Invoke-WebRequest "http://localhost:18080/actuator/health"
Invoke-WebRequest "http://localhost:18080/actuator/prometheus"
```
