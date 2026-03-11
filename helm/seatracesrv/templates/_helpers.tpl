{{/*
Expand the name of the chart.
*/}}
{{- define "seatracesrv.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "seatracesrv.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "seatracesrv.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "seatracesrv.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "seatracesrv.selectorLabels" -}}
app.kubernetes.io/name: {{ include "seatracesrv.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Secret name to use — either the auto-created one or an existing one.
*/}}
{{- define "seatracesrv.secretName" -}}
{{- if .Values.secret.existingSecret }}
{{- .Values.secret.existingSecret }}
{{- else }}
{{- include "seatracesrv.fullname" . }}-secrets
{{- end }}
{{- end }}

{{/*
Catalog-worker fully-qualified name.
*/}}
{{- define "catalogWorker.fullname" -}}
{{- printf "%s-catalog-worker" (include "seatracesrv.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Catalog-worker common labels.
*/}}
{{- define "catalogWorker.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "catalogWorker.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Catalog-worker selector labels.
*/}}
{{- define "catalogWorker.selectorLabels" -}}
app.kubernetes.io/name: {{ include "seatracesrv.name" . }}-catalog-worker
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
