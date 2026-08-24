{{- define "claims.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: claims-platform
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{- end }}
