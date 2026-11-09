import { ToastState } from "../components/widgets";
import { MascotState } from "../components/mascot";
import { HomeRoom } from "../three/homeRoom";

export const PageHome = 0;
export const PageDiary = 1;
export const PageRemote = 2;
export const PageAR = 3;
export const PageSettings = 4;
export const PageCount = 5;

/** ホーム画面の状態。Kotlin版 Pages.kt の HomeUi 相当。 */
export class HomeUi {
  readonly room: HomeRoom;
  stateText = "こんにちは！";
  readonly mascot = new MascotState();
  readonly toast = new ToastState();
  private listeners: Array<() => void> = [];

  constructor() {
    this.room = new HomeRoom(false);
    this.room.onStatus = (s) => {
      this.stateText = s;
      this.notify();
    };
    this.room.onModelBadge = (ok, text) => this.toast.show(ok ? "Happy" : "Sleepy", text, ok ? 2.8 : 0);
    this.room.onFaceReaction = (face, seconds) => this.mascot.react(face, seconds);
    void this.room.reportModelState();
  }

  get aiEnabled(): boolean {
    return this.room.aiEnabled;
  }
  toggleAIWalk(on: boolean): void {
    this.room.toggleAIWalk(on);
    this.notify();
  }

  subscribe(l: () => void): () => void {
    this.listeners.push(l);
    return () => {
      this.listeners = this.listeners.filter((x) => x !== l);
    };
  }
  private notify(): void {
    for (const l of this.listeners.slice()) l();
  }
}

/** AR タブの状態。Kotlin版 ARPageUi 相当。カメラAR本体は Phase 6/7 で実装する。 */
export class ARPageUi {
  readonly room: HomeRoom;
  arMode = false;
  editStateText = "面をタップして付け替えよう";
  readonly editMascot = new MascotState();
  readonly arToast = new ToastState();
  private listeners: Array<() => void> = [];

  constructor() {
    this.room = new HomeRoom(true);
    this.room.onStatus = (s) => {
      this.editStateText = s;
      this.notify();
    };
    this.room.onModelBadge = (ok, text) => this.arToast.show(ok ? "Happy" : "Sleepy", text, ok ? 2.8 : 0);
    this.room.onFaceReaction = (face, seconds) => this.editMascot.react(face, seconds);
    this.room.onFaceSelected = () => this.notify();
    this.room.onModulesChanged = () => this.notify();
    void this.room.reportModelState();
  }

  subscribe(l: () => void): () => void {
    this.listeners.push(l);
    return () => {
      this.listeners = this.listeners.filter((x) => x !== l);
    };
  }
  private notify(): void {
    for (const l of this.listeners.slice()) l();
  }
}

/** マーカー作成シートの開閉状態。Phase 5 で内容を実装する。 */
export class MarkerSheetState {
  isOpen = false;
  open(): void {
    this.isOpen = true;
  }
  close(): void {
    this.isOpen = false;
  }
}

/** アプリ全体の状態。Kotlin版 AppState 相当。 */
export class AppState {
  readonly home = new HomeUi();
  readonly ar = new ARPageUi();
  readonly markerSheet = new MarkerSheetState();
}
