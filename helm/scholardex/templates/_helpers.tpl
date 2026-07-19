{{/* Fixed resource names — must stay stable: live objects were adopted under these names. */}}
{{- define "scholardex.labels" -}}
app.kubernetes.io/name: scholardex
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
