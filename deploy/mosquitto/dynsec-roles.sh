# Роли Dynamic Security продукта — общий источник для dynsec-dev.sh (стенд) и интеграционных тестов server:data.
# Не исполняется сам: подключается в sh-скрипт, где уже определены
#   run <dynsec-команда…> — выполнить и упасть при ошибке; try <…> — выполнить, ошибку игнорировать;
#   has_role <имя> — роль уже есть.
# ACL применяются заново при каждом запуске (сначала снимаются, потом добавляются) — скрипт идемпотентен.

# P5: всё запрещено по умолчанию; отписка безвредна, но запрет ломает чистое отключение
run setDefaultACLAccess publishClientSend deny
run setDefaultACLAccess publishClientReceive deny
run setDefaultACLAccess subscribe deny
run setDefaultACLAccess unsubscribe allow

# device (P5, P6): публикует только свои входящие каналы бэкенда, получает только desired и cmd/req; %u = deviceId
has_role device || run createRole device
for t in status telemetry probe events shadow/reported cmd/res; do
  try removeRoleACL device publishClientSend "rf/v1/dev/%u/$t"
  run addRoleACL device publishClientSend "rf/v1/dev/%u/$t" allow
done
for t in shadow/desired cmd/req; do
  try removeRoleACL device subscribePattern "rf/v1/dev/%u/$t"
  run addRoleACL device subscribePattern "rf/v1/dev/%u/$t" allow
  try removeRoleACL device publishClientReceive "rf/v1/dev/%u/$t"
  run addRoleACL device publishClientReceive "rf/v1/dev/%u/$t" allow
done

# backend-service (реш. 230): shared-подписка группы backend на каналы всех устройств (DynSec сверяет подписку с
# полным фильтром `$share/backend/…`), получение этих сообщений, публикация desired и cmd/req любому устройству,
# управление учётками устройств через $CONTROL/dynamic-security.
has_role backend-service || run createRole backend-service
for t in status telemetry probe events shadow/reported cmd/res; do
  try removeRoleACL backend-service subscribePattern "\$share/backend/rf/v1/dev/+/$t"
  run addRoleACL backend-service subscribePattern "\$share/backend/rf/v1/dev/+/$t" allow
  try removeRoleACL backend-service publishClientReceive "rf/v1/dev/+/$t"
  run addRoleACL backend-service publishClientReceive "rf/v1/dev/+/$t" allow
done
# status — только очистка retained при выводе из эксплуатации (P8)
for t in shadow/desired cmd/req status; do
  try removeRoleACL backend-service publishClientSend "rf/v1/dev/+/$t"
  run addRoleACL backend-service publishClientSend "rf/v1/dev/+/$t" allow
done
for acl in publishClientSend publishClientReceive subscribePattern; do
  try removeRoleACL backend-service "$acl" '$CONTROL/dynamic-security/#'
  run addRoleACL backend-service "$acl" '$CONTROL/dynamic-security/#' allow
done
