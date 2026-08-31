export interface SelectOption<V = string | number> {
  label: string
  value: V
  disabled?: boolean
}
