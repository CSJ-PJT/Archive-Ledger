Archive-Ledger · Logistics-aware Financial Ledger Backend

Archive-Ledger는 Archive-Nexus direct 비용 이벤트와 Archive-Logistics 물류비 확정 이벤트를 수신해 거래 정규화, 복식 원장 기록, 승인 필요 여부 판단, 정산 제외, 대사 집계를 처리하는 Spring Boot 금융 백엔드입니다. source별 이벤트 구분, idempotency key 중복 방지, debit/credit 균형 검증으로 제조 → 물류 → 금융성 정산 흐름을 안정적으로 연결했습니다.
