Archive-Ledger · Logistics-aware Financial Ledger Backend  
Archive-Logitics에서 발행한 물류비 확정 이벤트를 수신해 거래 정규화, 복식 원장 기록, 승인 필요 여부 판단, 정산 제외, 대사 집계를 처리하도록 Archive-Ledger를 확장했습니다. Nexus 직접 비용 이벤트와 Logitics 경유 물류비 이벤트를 source별로 구분하고, idempotency key 기반 중복 방지와 debit/credit 균형 검증을 통해 제조 → 물류 → 금융성 정산 흐름을 안정적으로 연결했습니다.
