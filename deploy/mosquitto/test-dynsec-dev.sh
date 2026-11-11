#!/usr/bin/env bash
# Проверка dynsec-dev.sh на живом стенде: ACL роли device (по направлениям, %u), clientId = deviceId, идемпотентность.
# Предусловия: стенд поднят (deploy/README.md), dynsec-dev.sh выполнен. Пароли читаются из out/dev-credentials.txt и не печатаются.
# Скрипт не мешает работающим агентам и подписчикам оператора (решение 178):
# - устройство — отдельный тестовый клиент dev-acl-test (его создаёт dynsec-dev.sh), а не dev-emu-36: подключение с
#   clientId работающего агента вытеснило бы его и снесло бы его постоянную сессию;
# - у оператора свои clientId (acl-test-op-*): op-sub/op-pub из README заняты живой подпиской при валидации;
# - подписчики берут -R (retained status/desired со стенда не считаются полученными) и узкий топик, а не rf/v1/#.
set -uo pipefail
cd "$(dirname "$0")/.."

failures=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; failures=$((failures + 1)); }
die() { echo "FAIL: $*" >&2; exit 1; }

T=dev-acl-test
creds=out/dev-credentials.txt
[ -s "$creds" ] || die "нет $creds: сначала mosquitto/dynsec-dev.sh"
pw() { awk -v n="$1" '$1==n {print $2}' "$creds"; }
for u in operator "$T"; do [ -n "$(pw "$u")" ] || die "в $creds нет пароля для $u: выполните mosquitto/dynsec-dev.sh"; done
OP="$(pw operator)"
DEV="$(pw "$T")"

# Скрипт со stdin выполняется в контейнере брокера (пароли не попадают в argv хоста)
broker() {
  {
    cat <<SH
OP='$OP'
DEV='$DEV'
T='$T'
O="-h localhost -p 8883 --cafile /mosquitto/certs/ca.crt -V mqttv5"
SH
    cat
  } | docker compose exec -T mosquitto sh -s 2>&1
}

# 1. Публикация в свой топик доходит до подписчика operator
out="$(broker <<'SH'
mosquitto_sub $O -u operator -P "$OP" -i acl-test-op-sub -R -t "rf/v1/dev/$T/telemetry" -C 1 -W 5 >/tmp/t1.out 2>&1 &
sleep 1
mosquitto_pub $O -u "$T" -P "$DEV" -i "$T" -q 1 -t "rf/v1/dev/$T/telemetry" -m own-ok 2>&1
wait
cat /tmp/t1.out
SH
)"
grep -q '^own-ok$' <<<"$out" && ! grep -qiE 'not authori[sz]ed' <<<"$out" \
  && pass "$T -> rf/v1/dev/$T/telemetry доходит до operator" \
  || fail "публикация в свой топик не дошла: $out"

# 2-3. Чужой топик и свой, но «не в ту сторону» (cmd/req): не доходит, PUBACK с 0x87 (Not authorized).
# Подписчик слушает весь интервал -W (без -C 1): ранний чужой трафик не делает проверку «ничего не получил» пустой.
for topic in rf/v1/dev/dev-emu-31/telemetry "rf/v1/dev/$T/cmd/req"; do
  out="$(broker <<SH
mosquitto_sub \$O -u operator -P "\$OP" -i acl-test-op-sub -R -t '$topic' -W 4 >/tmp/t2.out 2>&1 &
sleep 1
mosquitto_pub \$O -u "\$T" -P "\$DEV" -i "\$T" -q 1 -t '$topic' -m forbidden 2>&1
wait
echo "--- operator received:"
cat /tmp/t2.out
SH
)"
  echo "    [$topic] вывод mosquitto_pub / operator:"; sed 's/^/      /' <<<"$out"
  pub_part="${out%%--- operator received:*}"
  recv_part="${out#*--- operator received:}"
  grep -qiE 'not authori[sz]ed' <<<"$pub_part" && ! grep -q forbidden <<<"$recv_part" \
    && pass "$topic отклонён (Not authorized), operator ничего не получил" \
    || fail "$topic не отклонён как положено"
done

# 4. clientId не равный username: отказ в CONNACK
out="$(broker <<'SH'
mosquitto_pub $O -u "$T" -P "$DEV" -i acl-test-other -q 1 -t "rf/v1/dev/$T/telemetry" -m x 2>&1
echo "rc=$?"
SH
)"
echo "    [clientId acl-test-other] вывод:"; sed 's/^/      /' <<<"$out"
grep -qiE 'not authori[sz]ed' <<<"$out" && ! grep -q 'rc=0' <<<"$out" \
  && pass "подключение $T с clientId acl-test-other отклонено" \
  || fail "clientId acl-test-other не отклонён"

# 5. Подписка: на чужой cmd/req — SUBACK 0x87 (135); на свои desired и cmd/req — разрешена
sub_code() {
  broker <<SH | grep -oE 'Subscribed \(mid: 1\): [0-9]+' | head -1
mosquitto_sub \$O -d -u "\$T" -P "\$DEV" -i "\$T" -q 1 -R -t '$1' -C 1 -W 2 2>&1
SH
}
c="$(sub_code rf/v1/dev/dev-emu-31/cmd/req)"
[ "$c" = 'Subscribed (mid: 1): 135' ] && pass "подписка на rf/v1/dev/dev-emu-31/cmd/req отклонена (SUBACK 0x87)" \
  || fail "подписка на чужой cmd/req: ${c:-нет SUBACK}"
c="$(sub_code '#')"
[ "$c" = 'Subscribed (mid: 1): 135' ] && pass "подписка device на # отклонена (SUBACK 0x87)" \
  || fail "подписка device на #: ${c:-нет SUBACK}"
c="$(sub_code "rf/v1/dev/$T/telemetry")"
[ "$c" = 'Subscribed (mid: 1): 135' ] && pass "подписка device на собственный telemetry отклонена (направление только на публикацию)" \
  || fail "подписка на собственный telemetry: ${c:-нет SUBACK}"
for t in "rf/v1/dev/$T/cmd/req" "rf/v1/dev/$T/shadow/desired"; do
  c="$(sub_code "$t")"
  [ "$c" = 'Subscribed (mid: 1): 1' ] && pass "подписка на $t разрешена (QoS 1)" || fail "подписка на $t: ${c:-нет SUBACK}"
done

# 6. Доставка команды устройству: operator публикует в cmd/req, device получает
out="$(broker <<'SH'
mosquitto_sub $O -u "$T" -P "$DEV" -i "$T" -q 1 -R -t "rf/v1/dev/$T/cmd/req" -C 1 -W 5 >/tmp/t6.out 2>&1 &
sleep 1
mosquitto_pub $O -u operator -P "$OP" -i acl-test-op-pub -q 1 -t "rf/v1/dev/$T/cmd/req" -m cmd-ok 2>&1
wait
cat /tmp/t6.out
SH
)"
grep -q '^cmd-ok$' <<<"$out" && pass "operator -> cmd/req доходит до $T" || fail "команда не дошла до устройства: $out"

# 7. Идемпотентность: повторный запуск, rc=0, файл учёток не изменился, пароли не печатаются
before_lines="$(wc -l <"$creds" | tr -d ' ')"
before_sum="$(shasum -a 256 "$creds" | awk '{print $1}')"
log="$(mktemp)"; trap 'rm -f "$log"' EXIT
mosquitto/dynsec-dev.sh >"$log" 2>&1; rc=$?
[ "$rc" = 0 ] && pass "повторный запуск dynsec-dev.sh: rc=0" || fail "повторный запуск: rc=$rc"
# bash 3.2 (macOS) при `set -u` и пустом массиве обрывал генерацию скрипта с rc=0: проверяем, что дошли до итога
grep -q '^DynSec: роли' "$log" && pass "повторный запуск дошёл до конца (ACL применены заново)" \
  || fail "повторный запуск оборвался до применения ACL: $(grep -v '^Warning' "$log" | head -3)"
[ "$before_lines" = "$(wc -l <"$creds" | tr -d ' ')" ] && [ "$before_sum" = "$(shasum -a 256 "$creds" | awk '{print $1}')" ] \
  && pass "файл учёток не изменился ($before_lines строк)" || fail "файл учёток изменился при повторном запуске"
[ "$(awk '{print $2}' "$creds" | grep -cFf - "$log")" = 0 ] && pass "в выводе повторного запуска нет паролей" \
  || fail "в выводе повторного запуска есть пароли"
[ "$(stat -f '%Lp' "$creds" 2>/dev/null || stat -c '%a' "$creds")" = 600 ] && pass "права файла учёток 600" || fail "права файла учёток не 600"
[ "$(( $(wc -l <"$creds") ))" = 16 ] && pass "в файле учёток 16 клиентов (12 dev-emu, 2 dev-do, $T, operator)" || fail "число строк учёток: $before_lines"
# Пароль после повторного запуска прежний: тестовый клиент всё ещё подключается
out="$(broker <<'SH'
mosquitto_pub $O -u "$T" -P "$DEV" -i "$T" -q 1 -t "rf/v1/dev/$T/status" -m still-ok 2>&1; echo "rc=$?"
SH
)"
grep -q 'rc=0' <<<"$out" && pass "пароль $T после повторного запуска прежний" || fail "$T не подключился после повторного запуска"

# 8. Учётка бэкенда (реш. 230): клиент backend с clientId backend и ролью backend-service, пароль — только в файле 600.
# Живые проверки ACL роли backend-service (shared-подписка, cmd/req, $CONTROL) — в интеграционных тестах server:data
# на тех же dynsec-roles.sh: подключение здесь с clientId backend вытеснило бы работающий бэкенд и сбросило бы его сессию.
bpw=out/backend-mqtt-password
[ -s "$bpw" ] && [ "$(stat -f '%Lp' "$bpw" 2>/dev/null || stat -c '%a' "$bpw")" = 600 ] \
  && pass "пароль backend в $bpw, права 600" || fail "нет $bpw или права не 600"
set -a; . ./.env; set +a
out="$({
  printf '%s\n' "ctrl() { mosquitto_ctrl -h localhost -p 8883 --cafile /mosquitto/certs/ca.crt -u admin -P '$DYNSEC_ADMIN_PASSWORD' \"\$@\"; }"
  echo 'ctrl dynsec getClient backend; ctrl dynsec getRole backend-service'
} | docker compose exec -T mosquitto sh -s 2>&1)"
grep -qE 'Clientid: +backend' <<<"$out" && grep -qE 'Roles: +backend-service' <<<"$out" \
  && pass "клиент backend: clientId backend, роль backend-service" || fail "клиент backend настроен не так: $(grep -E 'Clientid|Roles' <<<"$out")"
grep -qF 'subscribePattern     : allow : $share/backend/rf/v1/dev/+/telemetry' <<<"$out" \
  && grep -qF 'publishClientSend    : allow : rf/v1/dev/+/cmd/req' <<<"$out" \
  && ! grep -qE 'allow : #( |$)' <<<"$out" \
  && pass "роль backend-service: shared-подписка и cmd/req, без доступа ко всему дереву" \
  || fail "ACL роли backend-service не те"
[ "$(grep -cFf "$bpw" <<<"$out")" = 0 ] && pass "пароль backend не попал в вывод" || fail "пароль backend в выводе"

if [ "$failures" = 0 ]; then echo "dynsec-dev: all checks passed"; else echo "dynsec-dev: $failures check(s) FAILED"; exit 1; fi
