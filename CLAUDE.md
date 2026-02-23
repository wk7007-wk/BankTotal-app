# BankTotal 프로젝트 규칙

## 앱 개요
계좌잔고 위젯 + BBQ SFA BLOCK 금액 + 가계부(입출금 내역 관리)

## 개발 방침
- **오류/누락 절대 불가** — 사업 관련 금액 데이터. 수정 시 기존 기능 깨지지 않도록 반드시 검증
- 금액 표시, 계산, API 호출 로직 변경 시 이중 확인
- **SMS 권한 사용 금지** — RECEIVE_SMS/READ_SMS 권한은 Play Protect 차단 유발. NotificationListenerService로 메시지앱 알림 수신하여 처리
- **거래 누락 절대 금지** — 제외/필터 대상도 삭제하지 않고 음영처리로 표시

## 가계부 기능 (Firebase 기반)
- **탭 순서**: 출금 → 입금 → 전체 → 카테고리 (기본: 출금)
- **동일계좌 이체 감지**: 동일금액 + 5분 이내 + 다른 은행 + 입출금 반대 → 음영처리 + '이체' 태그, 합산 제외
- **중복 알림 감지**: 같은 은행 + 금액 + 타입, 60초 이내 → 음영처리 + '중복' 태그, 합산 제외
- **음영처리 원칙**: 제외 대상은 opacity 35% + 취소선으로 표시, 절대 숨기거나 삭제하지 않음. 금액 합산만 제외
- **서버 중복 방지**: FirebaseTransactionWriter에서 10초 내 동일 거래(은행+계좌+타입+금액) 스킵
- **입금**: 날짜별 합계 + 리스트식 표기
- **출금**: 리스트식 표기 + 같은 출금명은 카테고리 그룹핑
- **조회**: 날짜별 리스트, 카테고리별 합계

## 아키텍처 (알림 수신 기반)
- **NotificationListenerService** → 메시지앱(삼성/구글/기본) + 신한앱 알림 수신 → Firebase에 원문 + 파싱 결과 저장
- **지원 은행**: KB국민, 하나, 신협, 신한, 우리, 농협, 기업, 카카오, 토스 (메시지앱 알림으로 자동 식별)
- **로컬 Room**: 계좌 잔고(위젯용)만 유지, 거래내역은 Firebase
- **Firebase 경로**: `/banktotal/transactions/{id}`, `/banktotal/accounts/{id}`, `/banktotal/logs/{id}`
- **거래상대**: SMS 원문 저장 → 패턴 분석으로 점진적 파싱 개선
- **분석**: curl로 Firebase 데이터 읽어서 로그 분석/개선 가능

## WebView + GitHub Pages 구조
- **UI는 웹(`docs/index.html`)에서 처리** — 네이티브 UI 최소화
- WebView가 GitHub Pages 대시보드 로드, 오프라인 캐시 폴백
- NativeBridge: `getAccountsJson()`, `getPermissions()`, `openNotificationSettings()`, `openAccessibilitySettings()`, `getAppVersion()`
- UI 변경 시 `docs/index.html` 수정 후 push → 즉시 반영 (APK 재빌드 불필요)
- APK 재빌드는 네이티브 코드(.kt, .xml, AndroidManifest) 변경 시에만

## 로그분석 시스템
- `LogWriter.kt` → Firebase `/banktotal/logs`에 태그별 로그 저장 (최근 200건 순환)
- 태그: `[TX]` 거래수신, `[PARSE]` 파싱결과, `[ERR]` 에러, `[SYS]` 시스템
- 웹 대시보드 "더보기 → 로그 뷰어"에서 실시간 모니터링

## GitHub
- 레포: `wk7007-wk/BankTotal-app` (프로젝트명과 레포명 다름 주의)
- GitHub Pages: `https://wk7007-wk.github.io/BankTotal-app/` (docs/ 폴더, public 레포)
