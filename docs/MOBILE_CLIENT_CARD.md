# Mobilna karta klienta — 0.7.0

## Cel
Opiekun może przejść z Caller ID do mobilnej karty klienta i zobaczyć bez otwierania desktopowego CRM:
- klienta, numer, usługę, etap i opiekuna,
- aktualny status faktur po terminie,
- ostatnie rozmowy przychodzące/wychodzące wraz z wynikiem i czasem,
- ostatnie SMS-y przychodzące/wychodzące wraz z treścią i statusem,
- pracownika, który wykonał/odebrał zdarzenie,
- przycisk „Otwórz pełną kartę w CRM”.

## Prywatność
Caller ID może być widoczny na zablokowanym ekranie, ponieważ ma służyć do identyfikacji rozmówcy. Pełna historia klienta nie jest oznaczona `showWhenLocked`; jeśli urządzenie jest zablokowane, Android wymaga odblokowania przed przejściem do historii. Historia SMS nie jest utrwalana w cache aplikacji.

## Offline
Gdy CRM jest niedostępny, karta klienta może użyć zaszyfrowanego cache 0.5.0 do wyświetlenia nagłówka i ostatnio znanego statusu faktur. Historia rozmów/SMS pozostaje pusta i aplikacja wyświetla komunikat, że wymaga połączenia z CRM.

## Wejścia
1. Caller ID → „Historia klienta”.
2. Ekran główny → „Ostatnio rozpoznany klient” → „Otwórz historię klienta”.
