"""Local Windows pairing window; no HTTP administration endpoint."""
import argparse
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import qrcode
from PIL import ImageTk
from .host import Host


class Window:
    def __init__(self, root, host):
        self.root, self.host, self.invitation = root, host, None
        self.closing = False
        root.title('Device Bridge · 电脑连接')
        root.geometry('1100x950')
        body = ttk.Frame(root, padding=20)
        body.pack(fill='both', expand=True)
        ttk.Label(body, text='连接你的手机', font=('Microsoft YaHei UI', 20, 'bold')).pack(anchor='w')
        ttk.Label(body, text='扫码后核对两端六位数字，在本机选择允许访问的范围。').pack(anchor='w', pady=8)
        self.status = ttk.Label(body)
        self.status.pack(anchor='w')
        panel = ttk.Frame(body)
        panel.pack(fill='x', pady=12)
        left, right = ttk.Frame(panel), ttk.Frame(panel)
        left.pack(side='left', padx=(0, 20))
        right.pack(side='left', fill='both', expand=True)
        self.qr = ttk.Label(left, text='点击生成配对二维码', anchor='center', width=40)
        self.qr.pack()
        self.countdown = ttk.Label(left)
        self.countdown.pack(pady=5)
        ttk.Button(left, text='生成新的配对二维码', command=self.new_invite).pack(fill='x')
        ttk.Button(left, text='取消当前邀请', command=self.cancel_invite).pack(fill='x', pady=4)
        ttk.Label(right, text='待确认手机', font=('Microsoft YaHei UI', 12, 'bold')).pack(anchor='w')
        self.pending = tk.Listbox(right, height=3, exportselection=False)
        self.pending.pack(fill='x', pady=8)
        ttk.Label(right, text='授权会话（“发送”可触发原会话行为）').pack(anchor='w')
        self.grants = {}
        for label, target in host.service.policy.get('targets', {}).items():
            if target.get('kind') != 'codex' or 'read' not in target.get('rights', []):
                continue
            row = ttk.Frame(right)
            row.pack(fill='x', pady=3)
            read, send = tk.BooleanVar(value=True), tk.BooleanVar(value=False)
            ttk.Checkbutton(row, text=str(target.get('displayName', label))[:30], variable=read).pack(side='left')
            ttk.Checkbutton(row, text='发送', variable=send, state='normal' if 'send' in target.get('rights', []) else 'disabled').pack(side='right')
            self.grants[label] = (read, send)
        ttk.Button(right, text='数字一致，确认配对', command=self.approve).pack(fill='x', pady=(12, 4))
        ttk.Button(right, text='拒绝所选请求', command=self.deny).pack(fill='x')
        ttk.Label(body, text='已配对设备', font=('Microsoft YaHei UI', 12, 'bold')).pack(anchor='w', pady=(12, 4))
        self.devices = tk.Listbox(body, height=5, exportselection=False)
        self.devices.pack(fill='x')
        ttk.Button(body, text='撤销所选设备', command=self.revoke).pack(anchor='e', pady=8)
        ttk.Label(body, text='电脑身份：'+host.identity['hostId'][:24]+'…').pack(anchor='w')
        ttk.Label(body, text='关闭窗口会停止服务。中继仅转发端到端加密流量。').pack(anchor='w', pady=8)
        root.protocol('WM_DELETE_WINDOW', self.close)
        self.pending_rows, self.device_rows = [], []
        self.refresh()

    def new_invite(self):
        try:
            self.invitation = self.host.pairing.invite()
            image = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, box_size=3, border=4)
            image.add_data(self.invitation['text'])
            image.make(fit=True)
            self.photo = ImageTk.PhotoImage(image.make_image().get_image())
            self.qr.configure(image=self.photo, text='')
        except Exception:
            messagebox.showerror('无法创建邀请', '本机身份或配对存储不可用。')

    def cancel_invite(self):
        if self.invitation:
            self.host.pairing.deny(self.invitation['packet']['inviteId'])
        self.invitation = None
        self.qr.configure(image='', text='邀请已取消')

    def approve(self):
        selection = self.pending.curselection()
        if not selection:
            return
        row = self.pending_rows[selection[0]]
        grants = {label: ['read']+(['send'] if send.get() else []) for label, (read, send) in self.grants.items() if read.get()}
        if not grants:
            messagebox.showinfo('请选择范围', '至少选择一条允许读取的会话。')
            return
        if not messagebox.askyesno('确认配对', f"手机：{row['phone_name']}\n核对码：{row['code']}\n请确认手机显示相同数字。"):
            return
        try:
            self.host.approve(row['id'], row['code'], grants)
            self.invitation = None
            self.qr.configure(image='', text='配对已完成')
        except Exception:
            messagebox.showerror('配对未完成', '邀请可能已过期、被取消或身份已登记。请保留手机记录并核对状态。')

    def deny(self):
        selection = self.pending.curselection()
        if selection:
            self.host.pairing.deny(self.pending_rows[selection[0]]['id'])

    def revoke(self):
        selection = self.devices.curselection()
        if not selection:
            return
        row = self.device_rows[selection[0]]
        if row['enabled'] and messagebox.askyesno('撤销手机', '停止这部手机的后续读取和发送？已经交接的任务可能继续执行。'):
            try:
                self.host.revoke(row['device'])
            except Exception:
                messagebox.showerror('撤销状态待核对', '请停止服务并检查本机状态。')

    def refresh(self):
        if self.closing:
            return
        self.status.configure(text=self.host.status)
        pending = self.host.pairing.pending()
        if pending != self.pending_rows:
            self.pending_rows = pending
            self.pending.delete(0, 'end')
            for row in pending:
                self.pending.insert('end', row['phone_name']+'    '+row['code'])
            if pending:
                self.pending.selection_set(0)
        devices = self.host.pairing.devices()
        if devices != self.device_rows:
            self.device_rows = devices
            self.devices.delete(0, 'end')
            for row in devices:
                self.devices.insert('end', row['phone_name']+' · '+('已授权' if row['enabled'] else '已撤销')+' · '+row['device'][6:18])
        if self.invitation:
            left = self.invitation['packet']['expiresAt']-int(time.time())
            self.countdown.configure(text=f'有效期剩余 {max(0, left)} 秒')
            if left <= 0:
                self.cancel_invite()
        else:
            self.countdown.configure(text='')
        self.root.after(500, self.refresh)

    def close(self):
        if self.closing:
            return
        self.closing = True
        self.root.withdraw()
        def finish():
            self.host.close()
            self.root.after(0, self.root.destroy)
        threading.Thread(target=finish, daemon=False).start()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config')
    parser.add_argument('--self-test', action='store_true', help='synthetic packaging smoke test; starts no network')
    args = parser.parse_args()
    if args.self_test:
        from pathlib import Path
        from .security import generate_identity, protect, unprotect
        assert unprotect(protect(b'synthetic')) == b'synthetic'
        assert len(generate_identity()[0]) == 64
        assert (Path(__file__).resolve().parents[1]/'desktop'/'runtime_worker.mjs').is_file()
        root = tk.Tk()
        root.withdraw()
        image = qrcode.make('synthetic packaging test')
        ImageTk.PhotoImage(image.get_image(), master=root)
        root.update()
        root.destroy()
        return
    if not args.config:
        root = tk.Tk()
        root.withdraw()
        args.config = filedialog.askopenfilename(title='选择本机连接配置 host.json', filetypes=[('Device Bridge 配置', '*.json')])
        root.destroy()
        if not args.config:
            return
    host = Host(args.config)
    try:
        host.start()
        root = tk.Tk()
        Window(root, host)
        root.mainloop()
    finally:
        if not host.stop_event.is_set():
            host.close()


if __name__ == '__main__':
    main()
