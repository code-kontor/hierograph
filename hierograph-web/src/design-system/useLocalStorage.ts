import { useState } from "react";

export function useLocalStorage<T>(
  key: string,
  initial: T,
): [T, (value: T) => void] {
  const [storedValue, setStoredValue] = useState<T>(() => {
    try {
      const item = localStorage.getItem(key);
      return item !== null ? (JSON.parse(item) as T) : initial;
    } catch {
      return initial;
    }
  });

  function setValue(value: T) {
    try {
      localStorage.setItem(key, JSON.stringify(value));
    } catch {
      // ignore write failures (private mode / storage full)
    }
    setStoredValue(value);
  }

  return [storedValue, setValue];
}
