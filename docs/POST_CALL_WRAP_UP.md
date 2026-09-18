# Podsumowanie rozmowy — 0.8.0

## Przepływ

1. Android kończy połączenie i `CallLogResolver` ustala rzeczywisty status oraz czas rozmowy.
2. Zdarzenie rozmowy trafia do `/api/mobile/call/event` z tym samym `event_uuid`.
3. Dla rozmowy odebranej aplikacja zapisuje zaszyfrowany lokalny kontekst w `CallWrapUpStore`.
4. `PostCallNotifier` publikuje zwykłe powiadomienie **Uzupełnij rozmowę**. Nie uruchamiamy aktywności automatycznie z tła.
5. Dotknięcie powiadomienia otwiera bezpośrednio `CallWrapUpActivity`.
6. Pracownik wybiera wynik rozmowy, może dopisać notatkę i ustawić datę/godzinę ponownego kontaktu.
7. Zapis trafia do szyfrowanej kolejki offline przez `/api/mobile/call/wrap-up`.
8. CRM aktualizuje istniejącą rozmowę po `event_uuid`.

## Prywatność

Pending wrap-up jest przechowywany w osobnej lokalnej bazie. Payload jest szyfrowany AES-GCM kluczem z Android Keystore. Nieupełnione wpisy starsze niż 7 dni są usuwane.

## Powiadomienia

Aplikacja korzysta ze zwykłego kanału `post_call_wrap_up`. Nie używa full-screen intent. Jeżeli użytkownik nie przyzna `POST_NOTIFICATIONS`, pending wrap-up pozostaje dostępny z ekranu głównego aplikacji w sekcji **Rozmowy do uzupełnienia**.

## Domyślne wyniki

- Kontakt skuteczny (`contact_successful`)
- Oddzwonić (`call_back`)
- Klient prosi o kontakt później (`client_requests_later`)
- Brak nowych ustaleń (`no_new_arrangements`)
- Nie wymaga dalszego kontaktu (`no_further_contact`)
